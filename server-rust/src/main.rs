//! ChildGuard parent collector server — pure std + serde_json.
//!
//! Same API contract as the legacy Node server so the Android app and the
//! Termux dashboard keep working unchanged:
//!
//!   GET  /health
//!   POST /api/register        (Bearer auth)
//!   POST /api/events          (Bearer auth)
//!   GET  /api/stats           (Bearer auth)
//!   GET  /api/alerts?limit=N  (Bearer auth)
//!   GET  /api/events?limit=N  (Bearer auth)
//!   GET  /api/export          (Bearer auth)
//!   GET  /api/stream          (SSE, Bearer auth)
//!
//! Security posture:
//!   - bearer token checked in constant time
//!   - body size cap, header line cap, total request deadline
//!   - non-UTF8 / malformed JSON rejected with 400
//!   - bounded in-memory state (events/alerts capped)
//!   - bounded concurrent connections
//!   - zero web-framework dependency (small attack surface)

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet, VecDeque};
use std::env;
use std::fmt::Write as _;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------
const MAX_BODY: usize = 2 * 1024 * 1024; // 2 MiB
const MAX_HEADER_REGION: usize = 64 * 1024; // 64 KiB for the header block
const MAX_EVENTS: usize = 5000;
const MAX_ALERTS: usize = 500;
const MAX_CHILDREN: usize = 2000;
const MAX_CONNECTIONS: usize = 256;
const SSE_HEARTBEAT_SECS: u64 = 25;
const MAX_DUPE_KEYS: usize = 20_000;
/// Field caps. The 2 MiB body limit bounds one request, not the stored state:
/// 5,000 events (or 2,000 children) of near-2 MiB strings would pin gigabytes
/// of RSS and make `/api/export` allocate that again. The Android client sends
/// at most a few hundred bytes per field, so these caps are far above real
/// data while keeping worst-case memory in the tens of MiB.
const MAX_ID_BYTES: usize = 128;
const MAX_TYPE_BYTES: usize = 64;
const MAX_PACKAGE_BYTES: usize = 128;
const MAX_DATA_BYTES: usize = 8 * 1024;
const MAX_CHILD_NAME_BYTES: usize = 128;
/// SSE streams are long-lived threads; cap them separately from the general
/// connection limit so 64 idle dashboards cannot occupy every request slot.
const MAX_SSE: usize = 64;
/// Upper bound on the persisted state file accepted at startup. The live caps
/// already limit what this process writes; this protects against a corrupt or
/// hand-edited file that would otherwise be read and re-serialized in full.
const MAX_STATE_BYTES: usize = 64 * 1024 * 1024; // 64 MiB
/// How often dirty state is flushed to disk. Writes used to happen inline on
/// every request, which made the collector disk-bound (~8 req/s).
const PERSIST_INTERVAL_MS: u64 = 2_000;
/// Total wall-clock budget for reading one request (headers + body). The
/// per-read timeout alone does not stop a slowloris that sends one byte just
/// before each 30 s deadline; this bounds how long one of the 256 connection
/// slots can be held.
const MAX_REQUEST_TIME: Duration = Duration::from_secs(30);
/// Ceiling for a single `read`. The effective timeout is the smaller of this
/// and whatever is left of the request budget.
const PER_READ_TIMEOUT: Duration = Duration::from_secs(30);
const TS_FUTURE_ALLOWANCE_MS: u64 = 3_600_000; // accept client clocks up to 1h ahead
const STATE_DIR_ENV: &str = "CHILDGUARD_DATA_DIR";

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
#[derive(Clone, Debug, Serialize, Deserialize)]
struct Event {
    id: String,
    ts: u64,
    #[serde(rename = "serverTs", default)]
    server_ts: u64,
    #[serde(rename = "type")]
    type_: String,
    package: String,
    data: String,
    #[serde(rename = "childId", default)]
    child: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Alert {
    id: String,
    ts: u64,
    severity: String,
    reason: String,
    detail: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Child {
    id: String,
    name: String,
    last_seen: u64,
    registered_at: u64,
}

struct AppState {
    token: String,
    events: VecDeque<Event>,
    alerts: VecDeque<Alert>,
    children: HashMap<String, Child>,
    sse: Vec<(String, mpsc::SyncSender<String>)>,
    seen: HashSet<u64>,
    /// Insertion order for `seen`, used to evict the oldest duplicate key
    /// instead of clearing the whole set (which briefly re-admits retries).
    /// u64 keys keep this ~160 kB at 20k entries instead of ~2 MB of strings.
    seen_order: VecDeque<u64>,
    /// Set by mutating handlers; the background persister flushes it.
    dirty: bool,
}

/// Lock the shared state without letting a poisoned mutex take the service
/// down. A panic in one request must not make every later request fail.
fn lock_state(state: &Arc<Mutex<AppState>>) -> std::sync::MutexGuard<'_, AppState> {
    state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// The bounded state as a JSON document.
fn snapshot(st: &AppState) -> serde_json::Value {
    serde_json::json!({
        "events": st.events,
        "alerts": st.alerts,
        "children": st.children.values().collect::<Vec<_>>(),
    })
}

/// Persist a snapshot as JSON, atomically (tmp file + rename) and readable by
/// the owner only: the file holds the child's captured messages, so it must
/// not be world-readable.
fn write_snapshot(snapshot: &serde_json::Value, dir: &std::path::Path) {
    // Only tighten a directory this process creates. If an operator points
    // CHILDGUARD_DATA_DIR at an existing directory, changing its mode could
    // break unrelated services (or /tmp).
    let created_dir = !dir.exists();
    if let Err(e) = fs::create_dir_all(dir) {
        eprintln!("persist mkdir failed: {}", e);
        return;
    }
    #[cfg(unix)]
    if created_dir {
        use std::os::unix::fs::PermissionsExt;
        if let Err(e) = fs::set_permissions(dir, fs::Permissions::from_mode(0o700)) {
            eprintln!("persist chmod dir failed: {}", e);
        }
    }

    let tmp = dir.join("state.tmp");
    let final_path = dir.join("state.json");
    let res = (|| -> std::io::Result<()> {
        #[allow(unused_mut)]
        let mut opts = OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }
        let mut f = opts.open(&tmp)?;
        // mode() is ignored when the temp file already exists (for example
        // after a crash), so force owner-only explicitly.
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            f.set_permissions(fs::Permissions::from_mode(0o600))?;
        }
        serde_json::to_writer(&mut f, snapshot).map_err(std::io::Error::other)?;
        // rename() is atomic, but without fsync a power loss can leave an
        // empty/partial file behind the rename. Flush the bytes first.
        f.sync_all()?;
        drop(f);
        fs::rename(&tmp, &final_path)?;
        // Persist the rename itself on filesystems that need a directory
        // fsync. Failure here is not fatal (some platforms disallow it).
        #[cfg(unix)]
        if let Ok(d) = fs::File::open(dir) {
            let _ = d.sync_all();
        }
        Ok(())
    })();
    if let Err(e) = res {
        eprintln!("persist failed: {}", e);
        let _ = fs::remove_file(&tmp);
    }
}

/// Persist the state now (used by tests and one-shot callers).
#[allow(dead_code)] // used by the test module
fn persist_state_to(st: &AppState, dir: &std::path::Path) {
    write_snapshot(&snapshot(st), dir);
}

/// Load previously persisted state at startup.
fn load_state(st: &mut AppState) {
    let Ok(dir) = env::var(STATE_DIR_ENV) else { return };
    if dir.is_empty() {
        return;
    }
    load_state_from(st, &std::path::Path::new(&dir).join("state.json"));
}

fn load_state_from(st: &mut AppState, path: &std::path::Path) {
    // The file is normally written by this process and already bounded, but a
    // corrupt or hand-edited state.json must not be able to defeat the same
    // caps the request path enforces (a 1 GB file would otherwise be loaded
    // into memory and then re-serialized on the first persist tick).
    let raw = match fs::read(path) {
        Ok(r) => r,
        Err(_) => return,
    };
    if raw.len() > MAX_STATE_BYTES {
        eprintln!(
            "state file {} is {} bytes, above the {} byte cap; ignoring it",
            path.display(),
            raw.len(),
            MAX_STATE_BYTES
        );
        return;
    }
    let parsed: serde_json::Value = match serde_json::from_slice(&raw) {
        Ok(v) => v,
        Err(_) => return,
    };
    if let Some(arr) = parsed.get("events").and_then(|v| v.as_array()) {
        // Load at most the cap, keeping the newest entries (the file stores
        // oldest-first, like the live VecDeque).
        let start = arr.len().saturating_sub(MAX_EVENTS);
        for ev in &arr[start..] {
            if let Ok(e) = serde_json::from_value::<Event>(ev.clone()) {
                st.events.push_back(e);
            }
        }
    }
    if let Some(arr) = parsed.get("alerts").and_then(|v| v.as_array()) {
        let start = arr.len().saturating_sub(MAX_ALERTS);
        for al in &arr[start..] {
            if let Ok(a) = serde_json::from_value::<Alert>(al.clone()) {
                st.alerts.push_back(a);
            }
        }
    }
    if let Some(arr) = parsed.get("children").and_then(|v| v.as_array()) {
        let start = arr.len().saturating_sub(MAX_CHILDREN);
        for ch in &arr[start..] {
            if let Ok(c) = serde_json::from_value::<Child>(ch.clone()) {
                st.children.insert(c.id.clone(), c);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
        use std::io::Read;
        let _ = f.read_exact(&mut buf);
    } else {
        // Fallback (shouldn't happen on Linux): mix time + pid.
        let t = now_ms();
        for (i, b) in buf.iter_mut().enumerate() {
            *b = ((t ^ (i as u64 * 0x9E3779B97F4A7C15)).wrapping_mul(0x2545F4914F6CDD1D) >> 32) as u8;
        }
    }
    let mut s = String::with_capacity(bytes * 2);
    for b in buf {
        let _ = write!(s, "{:02x}", b);
    }
    s
}

fn uid(prefix: &str) -> String {
    format!("{}_{}", prefix, random_hex(8))
}

/// Constant-time string comparison to avoid timing side channels.
fn constant_time_eq(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (x, y) in a.as_bytes().iter().zip(b.as_bytes()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn json_response(conn: &mut TcpStream, status: u16, value: &serde_json::Value) {
    let body = serde_json::to_vec(value).unwrap_or_else(|_| b"{\"error\":\"encode\"}".to_vec());
    write_html_response(conn, status, "application/json", &body, true);
}

fn write_html_response(
    conn: &mut TcpStream,
    status: u16,
    ctype: &str,
    body: &[u8],
    cors: bool,
) {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        408 => "Request Timeout",
        413 => "Payload Too Large",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "OK",
    };
    let mut head = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n",
        status,
        reason,
        ctype,
        body.len()
    );
    if cors {
        head.push_str("Access-Control-Allow-Origin: *\r\n");
        head.push_str("Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n");
        head.push_str("Access-Control-Allow-Headers: Content-Type,Authorization\r\n");
    }
    head.push_str("\r\n");
    let w1 = conn.write_all(head.as_bytes());
    let w2 = conn.write_all(body);
    let w3 = conn.flush();
    let _ = (w1, w2, w3);
}

fn parse_query(path: &str) -> (&str, HashMap<String, String>) {
    match path.find('?') {
        Some(i) => {
            let (p, q) = path.split_at(i);
            let mut map = HashMap::new();
            for pair in q[1..].split('&') {
                if let Some(eq) = pair.find('=') {
                    map.insert(pair[..eq].to_string(), pair[eq + 1..].to_string());
                } else if !pair.is_empty() {
                    map.insert(pair.to_string(), String::new());
                }
            }
            (p, map)
        }
        None => (path, HashMap::new()),
    }
}

// ---------------------------------------------------------------------------
// Request reading
// ---------------------------------------------------------------------------
struct Request {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() {
        return Some(0);
    }
    if haystack.len() < needle.len() {
        return None;
    }
    // bounds are safe here because haystack.len() >= needle.len()
    for i in 0..=(haystack.len() - needle.len()) {
        if &haystack[i..i + needle.len()] == needle {
            return Some(i);
        }
    }
    None
}

/// Truncate to at most `max_bytes` without splitting a UTF-8 character.
/// Client-supplied strings are otherwise bounded only by the 2 MiB body cap.
fn clip(s: &str, max_bytes: usize) -> String {
    if s.len() <= max_bytes {
        return s.to_string();
    }
    let mut end = max_bytes;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    s[..end].to_string()
}

fn read_request(stream: &mut TcpStream) -> Result<Request, u16> {
    read_request_with_budget(stream, MAX_REQUEST_TIME)
}

/// Arm the socket read timeout with whatever is left of the request budget.
///
/// A fixed 30 s socket timeout equal to the total budget is not a bound: the
/// read returns `WouldBlock` at 30 s and used to be mapped to a 400, while a
/// peer that dribbles a byte just before each expiry could hold a connection
/// slot forever. Re-arming with `remaining` makes the deadline authoritative
/// and lets a timed-out read be reported as 408.
fn arm_read_timeout(stream: &TcpStream, started: std::time::Instant, budget: Duration) -> Result<(), u16> {
    let remaining = budget.checked_sub(started.elapsed()).unwrap_or_default();
    if remaining.is_zero() {
        return Err(408);
    }
    let _ = stream.set_read_timeout(Some(remaining.min(PER_READ_TIMEOUT)));
    Ok(())
}

fn is_read_timeout(e: &std::io::Error) -> bool {
    matches!(
        e.kind(),
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
    )
}

/// What one `read` call means for the request loop.
#[derive(Debug, PartialEq, Eq)]
enum ReadStep {
    Data(usize),
    Eof,
    /// A signal interrupted the read. POSIX says to retry; treating it as a
    /// protocol error killed legitimate requests at random.
    Interrupted,
    Timeout,
    Fatal,
}

fn read_step(result: std::io::Result<usize>) -> ReadStep {
    match result {
        Ok(0) => ReadStep::Eof,
        Ok(n) => ReadStep::Data(n),
        Err(e) if e.kind() == std::io::ErrorKind::Interrupted => ReadStep::Interrupted,
        Err(e) if is_read_timeout(&e) => ReadStep::Timeout,
        Err(_) => ReadStep::Fatal,
    }
}

/// Read one request, giving up after `budget`. Split out from [read_request]
/// so a unit test can exercise the deadline with a short budget instead of
/// waiting 30 s.
fn read_request_with_budget(stream: &mut TcpStream, budget: Duration) -> Result<Request, u16> {
    let started = std::time::Instant::now();
    let mut buf: Vec<u8> = Vec::new();
    let mut tmp = [0u8; 4096];
    // Track where we last scanned so we don't re-scan the whole buffer on
    // every read (avoids O(n^2) CPU on slow/large requests).
    let mut scanned_up_to = 0usize;
    loop {
        arm_read_timeout(stream, started, budget)?;
        let n = match read_step(stream.read(&mut tmp)) {
            ReadStep::Data(n) => n,
            ReadStep::Eof => break,
            ReadStep::Interrupted => continue,
            ReadStep::Timeout => return Err(408),
            ReadStep::Fatal => return Err(400),
        };
        buf.extend_from_slice(&tmp[..n]);
        if buf.len() > MAX_BODY + 65_536 {
            return Err(413);
        }
        // Re-scan only the new bytes, but back up 3 to catch a needle that
        // straddles the previous/new boundary.
        let scan_from = scanned_up_to.saturating_sub(3);
        scanned_up_to = buf.len();
        if find_subsequence(&buf[scan_from..], b"\r\n\r\n").is_some() {
            break;
        }
    }

    let split = find_subsequence(&buf, b"\r\n\r\n").ok_or(400u16)?;
    if split > MAX_HEADER_REGION {
        return Err(413);
    }
    let head_bytes = &buf[..split];
    let mut body = buf[split + 4..].to_vec();

    let head_text = String::from_utf8_lossy(head_bytes);
    let mut lines = head_text.lines();
    let request_line = lines.next().unwrap_or("").trim();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let path = parts.next().unwrap_or("").to_string();
    if method.is_empty() || path.is_empty() {
        return Err(400);
    }

    let mut headers: HashMap<String, String> = HashMap::new();
    let mut content_length: Option<usize> = None;
    for line in lines {
        if let Some(colon) = line.find(':') {
            let key = line[..colon].trim().to_ascii_lowercase();
            let value = line[colon + 1..].trim().to_string();
            if key == "content-length" {
                content_length = value.trim().parse::<usize>().ok();
            }
            headers.insert(key, value);
        }
    }

    let cl = content_length.unwrap_or(0);
    if cl > MAX_BODY {
        return Err(413);
    }
    while body.len() < cl {
        arm_read_timeout(stream, started, budget)?;
        let n = match read_step(stream.read(&mut tmp)) {
            ReadStep::Data(n) => n,
            ReadStep::Eof => break,
            ReadStep::Interrupted => continue,
            ReadStep::Timeout => return Err(408),
            ReadStep::Fatal => return Err(400),
        };
        body.extend_from_slice(&tmp[..n]);
        if body.len() > MAX_BODY {
            return Err(413);
        }
    }
    body.truncate(cl);
    Ok(Request {
        method,
        path,
        headers,
        body,
    })
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------
fn authorized(headers: &HashMap<String, String>, token: &str) -> bool {
    let hdr = match headers.get("authorization") {
        Some(h) => h,
        None => return false,
    };
    let expected = format!("Bearer {}", token);
    constant_time_eq(hdr, &expected)
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------
/// Insert or touch a child and enforce the children-map cap (evicts the
/// least-recently-seen entries once the bound is exceeded). Shared by the
/// register and events handlers so neither path can grow the map unboundedly.
fn bump_child(st: &mut AppState, id: &str, now: u64, new_child: Child) {
    st.children
        .entry(id.to_string())
        .or_insert_with(|| new_child)
        .last_seen = now;

    if st.children.len() > MAX_CHILDREN {
        let mut all: Vec<(u64, String)> = st
            .children
            .iter()
            .map(|(id, c)| (c.last_seen, id.clone()))
            .collect();
        all.sort();
        let victims: Vec<String> = all
            .into_iter()
            .take(64)
            .map(|(_, id)| id)
            .collect();
        for v in victims {
            st.children.remove(&v);
        }
    }
}

/// Unauthenticated status probe. It must not reveal anything about the token:
/// this endpoint is reachable by anyone who can see the port.
fn handle_health() -> (u16, serde_json::Value) {
    (200, serde_json::json!({ "ok": true, "service": "childguard", "uptime": now_ms() }))
}

fn handle_register(state: &Arc<Mutex<AppState>>, body: &[u8]) -> Result<(u16, serde_json::Value), u16> {
    if body.len() > MAX_BODY {
        return Err(413);
    }
    let parsed: serde_json::Value = serde_json::from_slice(body).map_err(|_| 400u16)?;
    let name = parsed
        .get("name")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Child".to_string());
    let name = clip(&name, MAX_CHILD_NAME_BYTES);
    let id = uid("cg");
    let now = now_ms();
    let mut st = lock_state(state);
    bump_child(
        &mut st,
        &id,
        now,
        Child {
            id: id.clone(),
            name,
            last_seen: now,
            registered_at: now,
        },
    );
    st.dirty = true;
    Ok((200, serde_json::json!({ "childId": id })))
}

/// Record a duplicate-detection key and keep the set bounded by evicting the
/// **oldest** key. Clearing the set at the cap (the previous behavior) made an
/// in-flight retry of a still-retained event look new again, inflating counts
/// and re-raising alerts.
fn remember_seen(st: &mut AppState, key: u64) -> bool {
    if !st.seen.insert(key) {
        return false;
    }
    st.seen_order.push_back(key);
    while st.seen_order.len() > MAX_DUPE_KEYS {
        if let Some(oldest) = st.seen_order.pop_front() {
            st.seen.remove(&oldest);
        }
    }
    true
}

fn handle_events(state: &Arc<Mutex<AppState>>, body: &[u8]) -> Result<(u16, serde_json::Value), u16> {
    if body.len() > MAX_BODY {
        return Err(413);
    }
    let parsed: serde_json::Value = serde_json::from_slice(body).map_err(|_| 400u16)?;
    let child_id_raw = parsed
        .get("childId")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if child_id_raw.is_empty() {
        return Err(400);
    }
    // Cap the id before it becomes a map key and part of the dedupe hash.
    let child_id = clip(&child_id_raw, MAX_ID_BYTES);

    // Borrow the parsed events instead of deep-cloning the array. A 5,000
    // event batch used to allocate a second full copy before doing any work.
    let raw_events = parsed.get("events");
    let incoming: &[serde_json::Value] = match raw_events {
        Some(serde_json::Value::Array(arr)) => arr.as_slice(),
        Some(obj @ serde_json::Value::Object(_)) => std::slice::from_ref(obj),
        _ => return Err(400),
    };

    let now = now_ms();
    let mut st = lock_state(state);

    // auto-register unknown child (parity with hardened Node server)
    bump_child(
        &mut st,
        &child_id,
        now,
        Child {
            id: child_id.clone(),
            name: "Child".to_string(),
            last_seen: now,
            registered_at: now,
        },
    );

    let last_event = incoming.last().cloned();
    let mut received = 0usize;
    for ev in incoming.iter().take(MAX_EVENTS) {
        // A malformed batch can contain strings/numbers/arrays. Node skips
        // them; storing one would inflate counts with an empty event.
        if !ev.is_object() {
            continue;
        }
        let type_ = clip(
            ev.get("type").and_then(|v| v.as_str()).unwrap_or("unknown"),
            MAX_TYPE_BYTES,
        );
        let package = clip(
            ev.get("package").and_then(|v| v.as_str()).unwrap_or(""),
            MAX_PACKAGE_BYTES,
        );
        let data = match ev.get("data") {
            Some(serde_json::Value::String(s)) => clip(s, MAX_DATA_BYTES),
            Some(v) => clip(&v.to_string(), MAX_DATA_BYTES),
            None => String::new(),
        };
        // A missing, null, zero, or non-integer ts means "now". Accepting 0
        // would file the event at 1970 and corrupt ordering/screen time;
        // Node rejects <= 0 the same way.
        let raw_ts = ev
            .get("ts")
            .and_then(|v| v.as_u64())
            .filter(|&t| t > 0)
            .unwrap_or(now);
        // Sanitize the client-supplied timestamp so a tampered/clock-skewed
        // device cannot inject absurd future timestamps (false ordering).
        let ts = raw_ts.min(now.saturating_add(TS_FUTURE_ALLOWANCE_MS));

        // Idempotency: same (child,type,package,data,ts) is only stored once,
        // so a retried upload can't inflate counts or duplicate alerts.
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        use std::hash::{Hash, Hasher};
        child_id.hash(&mut hasher);
        type_.hash(&mut hasher);
        package.hash(&mut hasher);
        data.hash(&mut hasher);
        ts.hash(&mut hasher);
        let key = hasher.finish();
        if !remember_seen(&mut st, key) {
            continue; // duplicate
        }

        let id = uid("e");
        let event = Event {
            id,
            ts,
            server_ts: now,
            type_: type_.clone(),
            package,
            data,
            child: child_id.clone(),
        };
        st.events.push_back(event);
        if st.events.len() > MAX_EVENTS {
            st.events.pop_front();
        }
        if type_ == "stranger_alert" || type_ == "stranger_notification" {
            // Severity reflects real risk: a stranger reaching the child is
            // high, a stranger notification is medium. Never "info".
            let severity = if type_ == "stranger_alert" { "high" } else { "medium" };
            let alert = Alert {
                id: uid("a"),
                ts,
                severity: severity.to_string(),
                reason: type_.clone(),
                detail: serde_json::to_string(ev).unwrap_or_default(),
            };
            st.alerts.push_back(alert);
            if st.alerts.len() > MAX_ALERTS {
                st.alerts.pop_front();
            }
        }
        received += 1;
    }
    st.dirty = true;

    // broadcast to SSE clients
    let ev_msg = serde_json::json!({ "type": "event", "childId": child_id, "event": last_event });
    let payload = format!("data: {}\n\n", ev_msg);
    let mut dead: Vec<usize> = Vec::new();
    for (i, (_, tx)) in st.sse.iter().enumerate() {
        if tx.try_send(payload.clone()).is_err() {
            dead.push(i);
        }
    }
    for i in dead.into_iter().rev() {
        st.sse.swap_remove(i);
    }

    Ok((200, serde_json::json!({ "ok": true, "received": received })))
}

fn handle_stats(state: &Arc<Mutex<AppState>>) -> (u16, serde_json::Value) {
    let st = lock_state(state);
    let mut by_type: HashMap<String, usize> = HashMap::new();
    for e in &st.events {
        *by_type.entry(e.type_.clone()).or_insert(0) += 1;
    }
    let now = now_ms();
    let children: Vec<serde_json::Value> = st
        .children
        .values()
        .map(|c| {
            serde_json::json!({
                "id": c.id,
                "name": c.name,
                "lastSeen": c.last_seen,
                "online": now.saturating_sub(c.last_seen) < 60_000
            })
        })
        .collect();
    let recent_alerts: Vec<serde_json::Value> = st.alerts.iter().rev().take(50).map(|a| {
        serde_json::json!({ "id": a.id, "ts": a.ts, "severity": a.severity, "reason": a.reason, "detail": a.detail })
    }).collect();
    (
        200,
        serde_json::json!({
            "children": children,
            "totalEvents": st.events.len(),
            "totalAlerts": st.alerts.len(),
            "byType": by_type,
            "recentAlerts": recent_alerts,
        }),
    )
}

fn handle_list_events(state: &Arc<Mutex<AppState>>, query: &HashMap<String, String>) -> (u16, serde_json::Value) {
    let limit = query.get("limit").and_then(|v| v.parse::<usize>().ok()).unwrap_or(200).min(5000);
    let st = lock_state(state);
    let events: Vec<serde_json::Value> = st.events.iter().rev().take(limit).map(|e| {
        serde_json::json!({ "id": e.id, "ts": e.ts, "serverTs": e.server_ts, "type": e.type_, "package": e.package, "data": e.data, "childId": e.child })
    }).collect();
    (200, serde_json::json!({ "events": events, "total": st.events.len() }))
}

fn handle_list_alerts(state: &Arc<Mutex<AppState>>, query: &HashMap<String, String>) -> (u16, serde_json::Value) {
    let limit = query.get("limit").and_then(|v| v.parse::<usize>().ok()).unwrap_or(100).min(500);
    let st = lock_state(state);
    let alerts: Vec<serde_json::Value> = st.alerts.iter().rev().take(limit).map(|a| {
        serde_json::json!({ "id": a.id, "ts": a.ts, "severity": a.severity, "reason": a.reason, "detail": a.detail })
    }).collect();
    (200, serde_json::json!({ "alerts": alerts, "total": st.alerts.len() }))
}

fn handle_export(state: &Arc<Mutex<AppState>>) -> (u16, serde_json::Value) {
    let st = lock_state(state);
    let events: Vec<serde_json::Value> = st.events.iter().rev().map(|e| {
        serde_json::json!({ "id": e.id, "ts": e.ts, "serverTs": e.server_ts, "type": e.type_, "package": e.package, "data": e.data, "childId": e.child })
    }).collect();
    let alerts: Vec<serde_json::Value> = st.alerts.iter().rev().map(|a| {
        serde_json::json!({ "id": a.id, "ts": a.ts, "severity": a.severity, "reason": a.reason, "detail": a.detail })
    }).collect();
    let mut by_type: HashMap<String, usize> = HashMap::new();
    for e in &st.events {
        *by_type.entry(e.type_.clone()).or_insert(0) += 1;
    }
    let now = now_ms();
    (
        200,
        serde_json::json!({
            "stats": {
                "totalEvents": st.events.len(),
                "totalAlerts": st.alerts.len(),
                "byType": by_type,
            },
            "events": events,
            "alerts": alerts,
            "exportedAt": now,
        }),
    )
}

// ---------------------------------------------------------------------------
// SSE handler (streaming)
// ---------------------------------------------------------------------------
/// Register one SSE listener if there is room. Returns false when the cap is
/// reached; the caller then answers 503 instead of pinning a thread.
fn add_sse_client(st: &mut AppState, id: String, tx: mpsc::SyncSender<String>) -> bool {
    if st.sse.len() >= MAX_SSE {
        return false;
    }
    st.sse.push((id, tx));
    true
}

fn handle_sse(
    state: &Arc<Mutex<AppState>>,
    mut conn: TcpStream,
) {
    let id = uid("sse");
    let (tx, rx) = mpsc::sync_channel::<String>(64);
    {
        let mut st = lock_state(state);
        if !add_sse_client(&mut st, id.clone(), tx) {
            drop(st);
            json_response(
                &mut conn,
                503,
                &serde_json::json!({ "error": "too many stream clients" }),
            );
            return;
        }
    }
    let _ = conn.write_all(
        b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n",
    );
    let _ = conn.write_all(format!("data: {}\n\n", serde_json::json!({ "type": "connected", "stats": handle_stats(state).1 })).as_bytes());
    let _ = conn.flush();

    'stream: loop {
        match rx.recv_timeout(Duration::from_secs(SSE_HEARTBEAT_SECS)) {
            Ok(msg) => {
                if conn.write_all(msg.as_bytes()).is_err() {
                    break 'stream;
                }
                let _ = conn.flush();
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                if conn.write_all(b": ping\n\n").is_err() {
                    break 'stream;
                }
                let _ = conn.flush();
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => break 'stream,
        }
    }
    let mut st = lock_state(state);
    st.sse.retain(|(sid, _)| *sid != id);
}

// ---------------------------------------------------------------------------
// Main accept loop
// ---------------------------------------------------------------------------
fn main() {
    let port: u16 = env::var("PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(38080);
    let bind = env::var("BIND").unwrap_or_else(|_| "0.0.0.0".to_string());
    let (token, token_generated) = match env::var("CHILDGUARD_TOKEN") {
        Ok(t) if !t.is_empty() => (t, false),
        _ => (random_hex(24), true),
    };

    let state = Arc::new(Mutex::new(AppState {
        token: token.clone(),
        events: VecDeque::new(),
        alerts: VecDeque::new(),
        children: HashMap::new(),
        sse: Vec::new(),
        seen: HashSet::new(),
        seen_order: VecDeque::new(),
        dirty: false,
    }));
    {
        let mut st = lock_state(&state);
        load_state(&mut st);
    }

    // Coalesced persistence: flush dirty state from a background thread so
    // request handling never waits on disk I/O. Worst case on a hard kill is
    // PERSIST_INTERVAL_MS of unflushed events.
    if let Ok(dir) = env::var(STATE_DIR_ENV) {
        if !dir.is_empty() {
            let pstate = Arc::clone(&state);
            std::thread::spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_millis(PERSIST_INTERVAL_MS));
                let snap = {
                    let mut st = lock_state(&pstate);
                    if !st.dirty {
                        continue;
                    }
                    st.dirty = false;
                    snapshot(&st)
                };
                write_snapshot(&snap, std::path::Path::new(&dir));
            });
        }
    }

    let listener = TcpListener::bind(format!("{}:{}", bind, port)).expect("bind failed");
    eprintln!("ChildGuard server (rust) running on http://{}:{}", bind, port);
    if token_generated {
        // Only echo a token the operator has never seen. A token they set
        // themselves does not belong in log files / journals.
        eprintln!("Generated parent token (set CHILDGUARD_TOKEN to choose one): {}", token);
    } else if token.len() < 16 {
        eprintln!(
            "WARNING: parent token is only {} characters; use 16+ random characters \
             (e.g. `head -c 24 /dev/urandom | base64`).",
            token.len()
        );
    }
    eprintln!("Endpoints: /health /api/register /api/events /api/stats /api/alerts /api/export /api/stream");

    let active = Arc::new(AtomicUsize::new(0));

    for conn in listener.incoming() {
        let Ok(stream) = conn else { continue };
        let state = Arc::clone(&state);
        let active = Arc::clone(&active);

        if active.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
            active.fetch_sub(1, Ordering::SeqCst);
            let mut s = stream;
            let _ = s.write_all(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            continue;
        }

        std::thread::spawn(move || {
            let _guard = ConnectionGuard(active.clone());
            let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
            let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
            let mut conn = stream;

            // read request
            let req = match read_request(&mut conn) {
                Ok(r) => r,
                Err(status) => {
                    let msg = match status {
                        408 => "request timeout",
                        413 => "payload too large",
                        _ => "bad request",
                    };
                    json_response(&mut conn, status, &serde_json::json!({ "error": msg }));
                    return;
                }
            };

            if req.method == "OPTIONS" {
                let _ = conn.write_all(
                    b"HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type,Authorization\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                );
                return;
            }

            let (path, query) = parse_query(&req.path);

            // auth required except health
            let token = lock_state(&state).token.clone();
            let needs_auth = path != "/health";
            if needs_auth && !authorized(&req.headers, &token) {
                json_response(&mut conn, 401, &serde_json::json!({ "error": "unauthorized" }));
                return;
            }

            let result: Result<(u16, serde_json::Value), u16> = match (req.method.as_str(), path) {
                ("GET", "/health") => Ok(handle_health()),
                ("POST", "/api/register") => handle_register(&state, &req.body),
                ("POST", "/api/events") => handle_events(&state, &req.body),
                ("GET", "/api/stats") => Ok(handle_stats(&state)),
                ("GET", "/api/alerts") => Ok(handle_list_alerts(&state, &query)),
                ("GET", "/api/events") => Ok(handle_list_events(&state, &query)),
                ("GET", "/api/export") => Ok(handle_export(&state)),
                ("GET", "/api/stream") => {
                    handle_sse(&state, conn);
                    return;
                }
                _ => Err(404),
            };

            match result {
                Ok((status, value)) => json_response(&mut conn, status, &value),
                Err(status) => {
                    let msg = match status {
                        404 => "not found",
                        408 => "request timeout",
                        413 => "payload too large",
                        _ => "bad request",
                    };
                    json_response(&mut conn, status, &serde_json::json!({ "error": msg }));
                }
            }
        });
    }
}

struct ConnectionGuard(Arc<AtomicUsize>);
impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    fn test_state(token: &str) -> Arc<Mutex<AppState>> {
        Arc::new(Mutex::new(AppState {
            token: token.to_string(),
            events: VecDeque::new(),
            alerts: VecDeque::new(),
            children: HashMap::new(),
            sse: Vec::new(),
            seen: HashSet::new(),
            seen_order: VecDeque::new(),
            dirty: false,
        }))
    }

    #[test]
    fn constant_time_eq_works() {
        assert!(constant_time_eq("abc", "abc"));
        assert!(!constant_time_eq("abc", "abd"));
        assert!(!constant_time_eq("abc", "abcd"));
        assert!(!constant_time_eq("", "a"));
        assert!(constant_time_eq("", ""));
    }

    #[test]
    fn auth_rejects_wrong_token() {
        let state = test_state("secret");
        let mut headers = HashMap::new();
        headers.insert("authorization".to_string(), "Bearer wrong".to_string());
        assert!(!authorized(&headers, &state.lock().unwrap().token));
        headers.insert("authorization".to_string(), "Bearer secret".to_string());
        assert!(authorized(&headers, &state.lock().unwrap().token));
    }

    #[test]
    fn register_and_events_flow() {
        let state = test_state("secret");
        let body = b"{\"name\":\"Eli\"}";
        let (code, val) = handle_register(&state, body).unwrap();
        assert_eq!(code, 200);
        let child_id = val["childId"].as_str().unwrap().to_string();
        assert!(child_id.starts_with("cg_"));

        let ev_body = format!(
            r#"{{"childId":"{}","events":[{{"type":"stranger_alert","package":"com.whatsapp","data":"hi cutie","ts":1}}]}}"#,
            child_id
        );
        let (code, val) = handle_events(&state, ev_body.as_bytes()).unwrap();
        assert_eq!(code, 200);
        assert_eq!(val["received"].as_u64().unwrap(), 1);

        let st = state.lock().unwrap();
        assert_eq!(st.events.len(), 1);
        assert_eq!(st.alerts.len(), 1);
        assert_eq!(st.events[0].type_, "stranger_alert");
        assert_eq!(st.children.len(), 1);
        assert!(st.children.contains_key(&child_id));
    }

    #[test]
    fn auto_register_unknown_child() {
        let state = test_state("secret");
        let ev_body = r#"{"childId":"cg_unknown","events":[{"type":"keylog","package":"x","data":"y","ts":1}]}"#;
        let (code, _) = handle_events(&state, ev_body.as_bytes()).unwrap();
        assert_eq!(code, 200);
        let st = state.lock().unwrap();
        assert!(st.children.contains_key("cg_unknown"));
    }

    #[test]
    fn malformed_body_is_400() {
        let state = test_state("secret");
        assert_eq!(handle_register(&state, b"not json").unwrap_err(), 400);
        assert_eq!(handle_events(&state, b"not json").unwrap_err(), 400);
        assert_eq!(handle_events(&state, b"{\"childId\":\"x\"}").unwrap_err(), 400);
    }

    #[test]
    fn events_cap_keeps_newest() {
        let state = test_state("secret");
        let child = "cg_cap";
        for i in 0..(MAX_EVENTS + 50) {
            let body = format!(
                r#"{{"childId":"{}","events":[{{"type":"keylog","package":"p","data":"{}","ts":{}}}]}}"#,
                child, i, i
            );
            let _ = handle_events(&state, body.as_bytes()).unwrap();
        }
        let st = state.lock().unwrap();
        assert_eq!(st.events.len(), MAX_EVENTS);
        // the oldest 50 were dropped; newest is MAX_EVENTS+49
        let newest = st.events.back().unwrap();
        assert_eq!(newest.data, format!("{}", MAX_EVENTS + 49));
        let oldest = st.events.front().unwrap();
        assert_eq!(oldest.data, format!("{}", 50));
    }

    #[test]
    fn children_cap_evicts_oldest() {
        let state = test_state("secret");
        let now = now_ms();
        // Insert more than the cap.
        for i in 0..(MAX_CHILDREN + 64) {
            let body = format!(r#"{{"name":"c{}"}}"#, i);
            let _ = handle_register(&state, body.as_bytes()).unwrap();
        }
        {
            let st = state.lock().unwrap();
            assert!(st.children.len() <= MAX_CHILDREN);
        }
        // Re-register one fresh child and ensure no panic, still bounded.
        let (code, val) = handle_register(&state, b"{\"name\":\"extra\"}").unwrap();
        assert_eq!(code, 200);
        assert!(val["childId"].as_str().unwrap().starts_with("cg_"));
        let st = state.lock().unwrap();
        assert!(st.children.len() <= MAX_CHILDREN);
        let _ = now;
    }

    #[test]
    fn stranger_alerts_get_meaningful_severity() {
        let state = test_state("secret");
        let body = r#"{"childId":"cg_sev","events":[
            {"type":"stranger_alert","package":"com.whatsapp","data":"dont tell your parents","ts":1},
            {"type":"stranger_notification","package":"com.whatsapp","data":"hey kid","ts":2}
        ]}"#;
        let (_c, _v) = handle_events(&state, body.as_bytes()).unwrap();
        let st = state.lock().unwrap();
        assert_eq!(st.alerts.len(), 2);
        assert_eq!(st.alerts[0].severity, "high");
        assert_eq!(st.alerts[1].severity, "medium");
    }

    #[test]
    fn health_never_discloses_the_token() {
        let (_code, val) = handle_health();
        let text = val.to_string();
        assert!(!text.contains("token"), "health must not mention the token: {}", text);
        assert_eq!(val["ok"], true);
    }

    #[test]
    fn persisted_state_is_owner_only() {
        let dir = std::env::temp_dir().join(format!("cg_perm_{}", now_ms()));
        let state = test_state("secret");
        {
            let st = state.lock().unwrap();
            persist_state_to(&st, &dir);
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(dir.join("state.json")).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600, "state.json must be owner-only, got {:o}", mode);
            let dmode = fs::metadata(&dir).unwrap().permissions().mode();
            assert_eq!(dmode & 0o777, 0o700, "new data dir must be owner-only, got {:o}", dmode);
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn events_accepts_a_single_object() {
        // The borrowed-slice path must still accept the object form.
        let state = test_state("secret");
        let body = r#"{"childId":"cg_one","events":{"type":"keylog","package":"p","data":"x","ts":7}}"#;
        let (code, val) = handle_events(&state, body.as_bytes()).unwrap();
        assert_eq!(code, 200);
        assert_eq!(val["received"].as_u64().unwrap(), 1);
    }

    #[test]
    fn register_does_not_echo_the_token() {
        let state = test_state("secret");
        let (_code, val) = handle_register(&state, b"{\"name\":\"Kid\"}").unwrap();
        assert!(val["childId"].as_str().unwrap().starts_with("cg_"));
        assert!(val.get("token").is_none(), "register must not echo the token");
    }

    #[test]
    fn duplicate_events_are_ignored() {
        let state = test_state("secret");
        let body = r#"{"childId":"cg_dup","events":[{"type":"keylog","package":"p","data":"hello","ts":5}]}"#;
        let (code, val) = handle_events(&state, body.as_bytes()).unwrap();
        assert_eq!(code, 200);
        assert_eq!(val["received"].as_u64().unwrap(), 1);
        // identical retry (e.g. failed-upload retry) must not duplicate
        let (_code, val) = handle_events(&state, body.as_bytes()).unwrap();
        assert_eq!(val["received"].as_u64().unwrap(), 0);
        let st = state.lock().unwrap();
        assert_eq!(st.events.len(), 1);
        assert_eq!(st.alerts.len(), 0);
    }

    #[test]
    fn dedupe_evicts_only_the_oldest_key() {
        // A full clear() at the cap would forget every recent key, so a
        // delayed retry of a still-retained event could be stored twice.
        let state = test_state("secret");
        let mut st = state.lock().unwrap();
        for i in 0..(MAX_DUPE_KEYS as u64 + 5) {
            assert!(remember_seen(&mut st, i));
        }
        assert_eq!(st.seen.len(), MAX_DUPE_KEYS);
        assert_eq!(st.seen_order.len(), MAX_DUPE_KEYS);
        assert!(!st.seen.contains(&0), "oldest keys must be evicted first");
        assert!(!st.seen.contains(&4));
        assert!(st.seen.contains(&5));
        assert!(st.seen.contains(&(MAX_DUPE_KEYS as u64 + 4)));
        // The newest key is still recognized as a duplicate.
        assert!(!remember_seen(&mut st, MAX_DUPE_KEYS as u64 + 4));
    }

    #[test]
    fn future_timestamp_is_sanitized() {
        let state = test_state("secret");
        // Bracket the call: handle_events() uses its own now_ms(), which can be
        // a millisecond later than a `now` captured before it. Comparing the
        // clamped value against the earlier timestamp made this test flaky.
        let before = now_ms();
        let future = before + 10_000_000_000; // ~116 days ahead
        let body = format!(
            r#"{{"childId":"cg_ts","events":[{{"type":"keylog","package":"p","data":"x","ts":{}}}]}}"#,
            future
        );
        let (_code, _) = handle_events(&state, body.as_bytes()).unwrap();
        let after = now_ms();
        let st = state.lock().unwrap();
        let ev = st.events.front().unwrap();
        assert!(
            ev.ts <= after + TS_FUTURE_ALLOWANCE_MS,
            "ts {} exceeded the clamp bound {}",
            ev.ts,
            after + TS_FUTURE_ALLOWANCE_MS
        );
        assert!(ev.ts < future, "future timestamp was not clamped at all");
        assert!(ev.server_ts >= before.saturating_sub(10_000));
    }

    #[test]
    fn persist_roundtrip() {
        let dir = std::env::temp_dir().join(format!("cg_persist_{}", now_ms()));
        let state = test_state("secret");
        let body = r#"{"childId":"cg_pers","events":[{"type":"stranger_alert","package":"p","data":"hey kid","ts":7}]}"#;
        let _ = handle_events(&state, body.as_bytes()).unwrap();
        {
            let st = state.lock().unwrap();
            persist_state_to(&st, &dir);
        }
        drop(state);

        // fresh state loading from disk should see the persisted event
        let state2 = test_state("secret");
        {
            let mut st = state2.lock().unwrap();
            load_state_from(&mut st, &dir.join("state.json"));
            assert_eq!(st.events.len(), 1);
            assert_eq!(st.alerts.len(), 1);
            assert_eq!(st.events[0].data, "hey kid");
            assert!(st.children.contains_key("cg_pers"));
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn load_state_caps_arrays_to_the_live_limits() {
        // A corrupt or hand-edited state.json must not defeat the caps the
        // request path enforces. The loader keeps the newest entries.
        let dir = std::env::temp_dir().join(format!("cg_loadcap_{}", now_ms()));
        std::fs::create_dir_all(&dir).unwrap();
        let events: Vec<serde_json::Value> = (0..(MAX_EVENTS + 10))
            .map(|i| {
                serde_json::json!({
                    "id": format!("e{}", i),
                    "ts": i as u64,
                    "serverTs": i as u64,
                    "type": "keylog",
                    "package": "p",
                    "data": format!("{}", i),
                    "childId": "c",
                })
            })
            .collect();
        let alerts: Vec<serde_json::Value> = (0..(MAX_ALERTS + 10))
            .map(|i| {
                serde_json::json!({
                    "id": format!("a{}", i),
                    "ts": i as u64,
                    "severity": "info",
                    "reason": "r",
                    "detail": "{}",
                })
            })
            .collect();
        let doc = serde_json::json!({
            "events": events,
            "alerts": alerts,
            "children": [],
        });
        let path = dir.join("state.json");
        std::fs::write(&path, serde_json::to_vec(&doc).unwrap()).unwrap();

        let state = test_state("secret");
        {
            let mut st = state.lock().unwrap();
            load_state_from(&mut st, &path);
            assert_eq!(st.events.len(), MAX_EVENTS);
            assert_eq!(st.alerts.len(), MAX_ALERTS);
            // Newest-first tail is retained, not the oldest head.
            assert_eq!(st.events.front().unwrap().data, "10");
            assert_eq!(
                st.events.back().unwrap().data,
                format!("{}", MAX_EVENTS + 9)
            );
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn load_state_ignores_an_oversized_file() {
        let dir = std::env::temp_dir().join(format!("cg_loadbig_{}", now_ms()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("state.json");
        // Valid-prefix-free padding is enough: the size gate must reject it
        // before any JSON parsing or allocation of the array.
        let big = vec![b' '; MAX_STATE_BYTES + 1];
        std::fs::write(&path, &big).unwrap();

        let state = test_state("secret");
        {
            let mut st = state.lock().unwrap();
            load_state_from(&mut st, &path);
            assert_eq!(st.events.len(), 0);
            assert_eq!(st.alerts.len(), 0);
            assert_eq!(st.children.len(), 0);
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn zero_or_absent_timestamp_falls_back_to_now() {
        let state = test_state("secret");
        let before = now_ms();
        let body = r#"{"childId":"cg_zero","events":[
            {"type":"keylog","package":"p","data":"zero","ts":0},
            {"type":"keylog","package":"p","data":"missing"},
            "not-an-object",
            42
        ]}"#;
        let (code, val) = handle_events(&state, body.as_bytes()).unwrap();
        assert_eq!(code, 200);
        assert_eq!(
            val["received"].as_u64().unwrap(),
            2,
            "non-object entries must be skipped and ts=0 must be stored as now"
        );
        let after = now_ms();
        let st = state.lock().unwrap();
        assert_eq!(st.events.len(), 2);
        for e in &st.events {
            assert!(
                e.ts >= before.saturating_sub(1_000),
                "ts {} was filed in the past",
                e.ts
            );
            assert!(e.ts <= after + TS_FUTURE_ALLOWANCE_MS);
        }
    }

    /// Build a connected pair and hand the server side to the reader. The
    /// client writes whatever partial request the test needs and then blocks
    /// reading until the server drops the socket, so it can never send an
    /// early EOF and turn a timeout into a 400 by accident.
    fn with_stalled_client<F>(write: F, budget: Duration) -> (Result<Request, u16>, Duration)
    where
        F: FnOnce(&mut TcpStream) + Send + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let client = std::thread::spawn(move || {
            let mut s = TcpStream::connect(addr).unwrap();
            write(&mut s);
            let _ = s.flush();
            // Block until the server closes; never half-close early.
            let mut b = [0u8; 1];
            let _ = s.read(&mut b);
        });
        let (mut server_side, _) = listener.accept().unwrap();
        let started = std::time::Instant::now();
        let result = read_request_with_budget(&mut server_side, budget);
        let elapsed = started.elapsed();
        drop(server_side);
        client.join().unwrap();
        (result, elapsed)
    }

    #[test]
    fn read_step_retries_eintr_and_classifies_timeouts() {
        use std::io::{Error, ErrorKind};
        assert_eq!(read_step(Ok(0)), ReadStep::Eof);
        assert_eq!(read_step(Ok(7)), ReadStep::Data(7));
        // A signal during a blocking read must not fail the request.
        assert_eq!(
            read_step(Err(Error::from(ErrorKind::Interrupted))),
            ReadStep::Interrupted
        );
        // Socket read timeouts are a 408, not a bad request.
        assert_eq!(
            read_step(Err(Error::from(ErrorKind::WouldBlock))),
            ReadStep::Timeout
        );
        assert_eq!(
            read_step(Err(Error::from(ErrorKind::TimedOut))),
            ReadStep::Timeout
        );
        assert_eq!(
            read_step(Err(Error::from(ErrorKind::ConnectionReset))),
            ReadStep::Fatal
        );
    }

    #[test]
    fn slow_headers_hit_the_deadline_and_return_408() {
        let (result, elapsed) = with_stalled_client(
            |s| {
                s.write_all(b"GET /health HTTP/1.1\r\nHost: x\r\nX-Slow: ")
                    .unwrap();
            },
            Duration::from_millis(250),
        );
        assert_eq!(Some(408), result.err(), "slow headers must be a timeout");
        assert!(elapsed >= Duration::from_millis(150), "returned early: {elapsed:?}");
        assert!(elapsed < Duration::from_secs(1), "deadline overshot: {elapsed:?}");
    }

    #[test]
    fn slow_body_hit_the_deadline_and_return_408() {
        let (result, elapsed) = with_stalled_client(
            |s| {
                s.write_all(b"POST /api/events HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\na")
                    .unwrap();
            },
            Duration::from_millis(250),
        );
        assert_eq!(Some(408), result.err(), "slow body must be a timeout");
        assert!(elapsed >= Duration::from_millis(150), "returned early: {elapsed:?}");
        assert!(elapsed < Duration::from_secs(1), "deadline overshot: {elapsed:?}");
    }

    #[test]
    fn complete_request_is_parsed_within_budget() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let client = std::thread::spawn(move || {
            let mut s = TcpStream::connect(addr).unwrap();
            s.write_all(b"POST /api/events HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}")
                .unwrap();
            let _ = s.shutdown(std::net::Shutdown::Write);
        });
        let (mut server_side, _) = listener.accept().unwrap();
        let req = read_request_with_budget(&mut server_side, Duration::from_secs(2)).unwrap();
        assert_eq!(req.method, "POST");
        assert_eq!(req.path, "/api/events");
        assert_eq!(req.body, b"{}".to_vec());
        client.join().unwrap();
    }

    #[test]
    fn oversized_fields_are_clipped_before_storage() {
        let state = test_state("secret");
        let body = format!(r#"{{"name":"{}"}}"#, "n".repeat(10_000));
        let (code, val) = handle_register(&state, body.as_bytes()).unwrap();
        assert_eq!(code, 200);
        let id = val["childId"].as_str().unwrap().to_string();
        {
            let st = state.lock().unwrap();
            let child = st.children.get(&id).unwrap();
            assert!(
                child.name.len() <= MAX_CHILD_NAME_BYTES,
                "name kept {} bytes",
                child.name.len()
            );
        }

        // Multi-byte data so clipping at a byte boundary would panic or
        // produce invalid UTF-8 if the helper were wrong.
        let batch = serde_json::json!({
            "childId": "c".repeat(10_000),
            "events": [{
                "type": "t".repeat(5_000),
                "package": "p".repeat(5_000),
                "data": "é".repeat(10_000),
            }],
        });
        let (code, val) =
            handle_events(&state, serde_json::to_vec(&batch).unwrap().as_slice()).unwrap();
        assert_eq!(code, 200);
        assert_eq!(val["received"].as_u64().unwrap(), 1);
        let st = state.lock().unwrap();
        let stored = st.events.back().unwrap();
        assert_eq!(stored.child.len(), MAX_ID_BYTES, "childId not clipped");
        assert_eq!(stored.type_.len(), MAX_TYPE_BYTES, "type not clipped");
        assert_eq!(stored.package.len(), MAX_PACKAGE_BYTES, "package not clipped");
        assert!(stored.data.len() <= MAX_DATA_BYTES, "data kept {} bytes", stored.data.len());
        assert_eq!(stored.data.len() % 2, 0, "clip split a UTF-8 character");
        assert!(stored.data.chars().all(|c| c == 'é'));
        assert!(st.children.contains_key(&stored.child));
    }

    #[test]
    fn sse_cap_rejects_clients_beyond_the_limit() {
        let state = test_state("secret");
        let mut keep = Vec::new();
        let mut st = state.lock().unwrap();
        for i in 0..MAX_SSE {
            let (tx, rx) = mpsc::sync_channel(1);
            assert!(add_sse_client(&mut st, format!("s{i}"), tx));
            keep.push(rx);
        }
        let (tx, rx) = mpsc::sync_channel(1);
        assert!(!add_sse_client(&mut st, "overflow".to_string(), tx));
        keep.push(rx);
        assert_eq!(st.sse.len(), MAX_SSE);
        drop(st);
        drop(keep);
    }

    #[test]
    fn query_parser_works() {
        let (p, q) = parse_query("/api/events?limit=77&foo=bar");
        assert_eq!(p, "/api/events");
        assert_eq!(q.get("limit").unwrap(), "77");
        assert_eq!(q.get("foo").unwrap(), "bar");
    }
}
