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
//!   - body size cap, header line cap, per-connection timeout
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
    seen: HashSet<String>,
}

/// Persist the full (bounded) state as JSON. Location controlled by the
/// CHILDGUARD_DATA_DIR env var; when unset the server runs purely in-memory.
/// Writes atomically (tmp file + rename) so a crash can't corrupt data.
fn persist_state(st: &AppState) {
    let Ok(dir) = env::var(STATE_DIR_ENV) else { return };
    if dir.is_empty() {
        return;
    }
    persist_state_to(st, std::path::Path::new(&dir));
}

fn persist_state_to(st: &AppState, dir: &std::path::Path) {
    if let Err(e) = fs::create_dir_all(dir) {
        eprintln!("persist mkdir failed: {}", e);
        return;
    }
    let snapshot = serde_json::json!({
        "events": st.events,
        "alerts": st.alerts,
        "children": st.children.values().collect::<Vec<_>>(),
    });
    let tmp = dir.join("state.tmp");
    let final_path = dir.join("state.json");
    let res = (|| -> std::io::Result<()> {
        let mut f = OpenOptions::new().write(true).create(true).truncate(true).open(&tmp)?;
        serde_json::to_writer(&mut f, &snapshot).map_err(std::io::Error::other)?;
        f.flush()?;
        drop(f);
        fs::rename(&tmp, &final_path)?;
        Ok(())
    })();
    if let Err(e) = res {
        eprintln!("persist failed: {}", e);
        let _ = fs::remove_file(&tmp);
    }
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
    let raw = match fs::read(path) {
        Ok(r) => r,
        Err(_) => return,
    };
    let parsed: serde_json::Value = match serde_json::from_slice(&raw) {
        Ok(v) => v,
        Err(_) => return,
    };
    if let Some(arr) = parsed.get("events").and_then(|v| v.as_array()) {
        for ev in arr {
            if let Ok(e) = serde_json::from_value::<Event>(ev.clone()) {
                st.events.push_back(e);
            }
        }
    }
    if let Some(arr) = parsed.get("alerts").and_then(|v| v.as_array()) {
        for al in arr {
            if let Ok(a) = serde_json::from_value::<Alert>(al.clone()) {
                st.alerts.push_back(a);
            }
        }
    }
    if let Some(arr) = parsed.get("children").and_then(|v| v.as_array()) {
        for ch in arr {
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

fn read_request(stream: &mut TcpStream) -> Result<Request, u16> {
    let mut buf: Vec<u8> = Vec::new();
    let mut tmp = [0u8; 4096];
    // Track where we last scanned so we don't re-scan the whole buffer on
    // every read (avoids O(n^2) CPU on slow/large requests).
    let mut scanned_up_to = 0usize;
    loop {
        let n = stream.read(&mut tmp).map_err(|_| 400u16)?;
        if n == 0 {
            break;
        }
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
        let n = stream.read(&mut tmp).map_err(|_| 400u16)?;
        if n == 0 {
            break;
        }
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
    let id = uid("cg");
    let now = now_ms();
    let mut st = state.lock().unwrap();
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
    persist_state(&st);
    Ok((
        200,
        serde_json::json!({ "childId": id, "token": st.token }),
    ))
}

fn handle_events(state: &Arc<Mutex<AppState>>, body: &[u8]) -> Result<(u16, serde_json::Value), u16> {
    if body.len() > MAX_BODY {
        return Err(413);
    }
    let parsed: serde_json::Value = serde_json::from_slice(body).map_err(|_| 400u16)?;
    let child_id = parsed
        .get("childId")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if child_id.is_empty() {
        return Err(400);
    }

    let raw_events = parsed.get("events");
    let mut incoming: Vec<serde_json::Value> = Vec::new();
    match raw_events {
        Some(serde_json::Value::Array(arr)) => incoming = arr.clone(),
        Some(obj @ serde_json::Value::Object(_)) => incoming.push(obj.clone()),
        _ => return Err(400),
    }

    let now = now_ms();
    let mut st = state.lock().unwrap();

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
    for ev in incoming.into_iter().take(MAX_EVENTS) {
        let type_ = ev.get("type").and_then(|v| v.as_str()).unwrap_or("unknown").to_string();
        let package = ev.get("package").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let data = match ev.get("data") {
            Some(serde_json::Value::String(s)) => s.clone(),
            Some(v) => v.to_string(),
            None => String::new(),
        };
        let raw_ts = ev.get("ts").and_then(|v| v.as_u64()).unwrap_or(now);
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
        let key = hasher.finish().to_string();
        if !st.seen.insert(key.clone()) {
            continue; // duplicate
        }
        if st.seen.len() > MAX_DUPE_KEYS {
            st.seen.clear();
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
                detail: serde_json::to_string(&ev).unwrap_or_default(),
            };
            st.alerts.push_back(alert);
            if st.alerts.len() > MAX_ALERTS {
                st.alerts.pop_front();
            }
        }
        received += 1;
    }
    persist_state(&st);

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
    let st = state.lock().unwrap();
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
    let st = state.lock().unwrap();
    let events: Vec<serde_json::Value> = st.events.iter().rev().take(limit).map(|e| {
        serde_json::json!({ "id": e.id, "ts": e.ts, "serverTs": e.server_ts, "type": e.type_, "package": e.package, "data": e.data, "childId": e.child })
    }).collect();
    (200, serde_json::json!({ "events": events, "total": st.events.len() }))
}

fn handle_list_alerts(state: &Arc<Mutex<AppState>>, query: &HashMap<String, String>) -> (u16, serde_json::Value) {
    let limit = query.get("limit").and_then(|v| v.parse::<usize>().ok()).unwrap_or(100).min(500);
    let st = state.lock().unwrap();
    let alerts: Vec<serde_json::Value> = st.alerts.iter().rev().take(limit).map(|a| {
        serde_json::json!({ "id": a.id, "ts": a.ts, "severity": a.severity, "reason": a.reason, "detail": a.detail })
    }).collect();
    (200, serde_json::json!({ "alerts": alerts, "total": st.alerts.len() }))
}

fn handle_export(state: &Arc<Mutex<AppState>>) -> (u16, serde_json::Value) {
    let st = state.lock().unwrap();
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
fn handle_sse(
    state: &Arc<Mutex<AppState>>,
    mut conn: TcpStream,
) {
    let id = uid("sse");
    let (tx, rx) = mpsc::sync_channel::<String>(64);
    {
        let mut st = state.lock().unwrap();
        st.sse.push((id.clone(), tx));
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
    let mut st = state.lock().unwrap();
    st.sse.retain(|(sid, _)| *sid != id);
}

// ---------------------------------------------------------------------------
// Main accept loop
// ---------------------------------------------------------------------------
fn main() {
    let port: u16 = env::var("PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(38080);
    let bind = env::var("BIND").unwrap_or_else(|_| "0.0.0.0".to_string());
    let token = env::var("CHILDGUARD_TOKEN").unwrap_or_else(|_| random_hex(24));

    let state = Arc::new(Mutex::new(AppState {
        token: token.clone(),
        events: VecDeque::new(),
        alerts: VecDeque::new(),
        children: HashMap::new(),
        sse: Vec::new(),
        seen: HashSet::new(),
    }));
    {
        let mut st = state.lock().unwrap();
        load_state(&mut st);
    }

    let listener = TcpListener::bind(format!("{}:{}", bind, port)).expect("bind failed");
    eprintln!("ChildGuard server (rust) running on http://{}:{}", bind, port);
    eprintln!("Parent token: {}", token);
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
                    json_response(&mut conn, status, &serde_json::json!({ "error": "bad request" }));
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
            let token = state.lock().unwrap().token.clone();
            let needs_auth = path != "/health";
            if needs_auth && !authorized(&req.headers, &token) {
                json_response(&mut conn, 401, &serde_json::json!({ "error": "unauthorized" }));
                return;
            }

            let result: Result<(u16, serde_json::Value), u16> = match (req.method.as_str(), path) {                ("GET", "/health") => Ok((
                    200,
                    serde_json::json!({ "ok": true, "token": format!("{}...", &token[..token.len().min(8)]), "uptime": now_ms() }),
                )),
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
    fn future_timestamp_is_sanitized() {
        let state = test_state("secret");
        let now = now_ms();
        let future = now + 10_000_000_000; // ~116 days ahead
        let body = format!(
            r#"{{"childId":"cg_ts","events":[{{"type":"keylog","package":"p","data":"x","ts":{}}}]}}"#,
            future
        );
        let (_code, _) = handle_events(&state, body.as_bytes()).unwrap();
        let st = state.lock().unwrap();
        let ev = st.events.front().unwrap();
        assert!(ev.ts <= now + TS_FUTURE_ALLOWANCE_MS);
        assert!(ev.server_ts >= now.saturating_sub(10_000));
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
    fn query_parser_works() {
        let (p, q) = parse_query("/api/events?limit=77&foo=bar");
        assert_eq!(p, "/api/events");
        assert_eq!(q.get("limit").unwrap(), "77");
        assert_eq!(q.get("foo").unwrap(), "bar");
    }
}
