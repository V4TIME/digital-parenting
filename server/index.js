const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 38080;
const TOKEN_GENERATED = !process.env.CHILDGUARD_TOKEN;
const AUTH_TOKEN = process.env.CHILDGUARD_TOKEN || crypto.randomBytes(24).toString('hex');

const MAX_BODY = 2 * 1024 * 1024;
const MAX_EVENTS = 5000;
const MAX_ALERTS = 500;
// Env-overridable so the smoke test can exercise LRU eviction with two
// children instead of registering 2,001 over HTTP.
const MAX_CHILDREN = (() => {
  const n = parseInt(process.env.CHILDGUARD_MAX_CHILDREN, 10);
  return Number.isFinite(n) && n > 0 ? n : 2000;
})();
const MAX_DUPE_KEYS = 20000;
// Field caps. The 2 MiB body limit bounds one request, not retained state:
// 5,000 events (or 2,000 children) of near-2 MiB strings would pin gigabytes
// of RSS and make /api/export allocate it again. Mirrors the Rust collector.
const MAX_ID_BYTES = 128;
const MAX_TYPE_BYTES = 64;
const MAX_PACKAGE_BYTES = 128;
const MAX_DATA_BYTES = 8 * 1024;
const TS_FUTURE_ALLOWANCE_MS = 60 * 60 * 1000;
// Cap SSE listeners the same way the Rust collector caps connections. One
// leaked browser tab must not pin unbounded memory. Env-overridable so the
// smoke test can exercise the limit with a single connection.
const MAX_STREAMS = (() => {
  const n = parseInt(process.env.CHILDGUARD_MAX_STREAMS, 10);
  return Number.isFinite(n) && n > 0 ? n : 64;
})();
const SSE_HEARTBEAT_MS = 25000;

const state = {
  children: new Map(),
  events: [],
  alerts: [],
  clients: new Set(),
  seen: new Set(),
};

function now() { return Date.now(); }
function uid() { return 'cg_' + crypto.randomBytes(8).toString('hex'); }

/**
 * Truncate to at most `maxBytes` without splitting a UTF-8 character or a
 * surrogate pair. Iterates code points only when the string is oversized.
 */
function clip(value, maxBytes) {
  const s = typeof value === 'string' ? value : '';
  if (Buffer.byteLength(s, 'utf8') <= maxBytes) return s;
  let out = '';
  let bytes = 0;
  for (const ch of s) {
    const n = Buffer.byteLength(ch, 'utf8');
    if (bytes + n > maxBytes) break;
    out += ch;
    bytes += n;
  }
  return out;
}

/** Constant-time bearer comparison (length check first, as in the Rust server). */
function timingSafeEqual(a, b) {
  const ba = Buffer.from(a || '', 'utf8');
  const bb = Buffer.from(b || '', 'utf8');
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function authorized(req) {
  return timingSafeEqual(req.headers.authorization || '', `Bearer ${AUTH_TOKEN}`);
}

function sendJson(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

/**
 * Read a request body with a hard cap. Exceeding the cap responds 413 and
 * discards the rest instead of growing an unbounded string in memory.
 */
function readBody(req, res, onBody) {
  let body = '';
  let size = 0;
  let tooLarge = false;
  req.setEncoding('utf8');
  req.on('data', chunk => {
    size += Buffer.byteLength(chunk);
    if (size > MAX_BODY) {
      tooLarge = true;
      return;
    }
    body += chunk;
  });
  req.on('end', () => {
    if (tooLarge) {
      sendJson(res, 413, { error: 'body too large' });
      return;
    }
    onBody(body);
  });
  req.on('error', () => {
    try { res.destroy(); } catch (e) {}
  });
}

function bumpChild(id, name) {
  if (state.children.size >= MAX_CHILDREN && !state.children.has(id)) {
    // Evict the least-recently-seen child, matching the Rust collector.
    // Deleting the first key inserted would drop a long-lived active child
    // while keeping a stale one that happened to register later.
    let oldestId = null;
    let oldestSeen = Infinity;
    for (const [cid, c] of state.children) {
      if (c.lastSeen < oldestSeen) {
        oldestSeen = c.lastSeen;
        oldestId = cid;
      }
    }
    if (oldestId !== null) state.children.delete(oldestId);
  }
  const child = { id, name, lastSeen: now(), registeredAt: now() };
  state.children.set(id, child);
  return child;
}

function broadcast(obj) {
  const payload = JSON.stringify(obj);
  for (const c of [...state.clients]) {
    try {
      c.write('data: ' + payload + '\n\n');
    } catch (e) {
      state.clients.delete(c);
    }
  }
}

/**
 * Store one event with the same normalization/idempotency as the Rust server:
 * bounded fields, clamped timestamp, and a duplicate hash so an upload retry
 * cannot inflate counts or alerts. Returns false for a duplicate.
 */
function addEvent(childId, ev) {
  const type = clip(typeof ev.type === 'string' ? ev.type : 'unknown', MAX_TYPE_BYTES);
  const pkg = clip(typeof ev.package === 'string' ? ev.package : '', MAX_PACKAGE_BYTES);
  const data = clip(typeof ev.data === 'string'
    ? ev.data
    : (ev.data == null ? '' : JSON.stringify(ev.data)), MAX_DATA_BYTES);
  const rawTs = Number(ev.ts);
  // Number(null) and Number('') are 0, which would store the event at 1970
  // and corrupt ordering/screen time. The Rust collector treats a missing or
  // non-numeric ts as "now"; match that (and reject 0/negative epochs).
  const ts = (Number.isFinite(rawTs) && rawTs > 0)
    ? Math.min(rawTs, now() + TS_FUTURE_ALLOWANCE_MS)
    : now();

  const key = crypto.createHash('sha1')
    .update(`${childId}\0${type}\0${pkg}\0${data}\0${ts}`)
    .digest('hex');
  if (state.seen.has(key)) return false;
  state.seen.add(key);
  // JS Sets iterate in insertion order, so evict only the oldest key. The
  // previous clear() forgot every recent key, letting an in-flight retry of a
  // still-retained event be stored a second time.
  while (state.seen.size > MAX_DUPE_KEYS) {
    state.seen.delete(state.seen.values().next().value);
  }

  const event = { id: uid(), ts, serverTs: now(), type, package: pkg, data, childId };
  state.events.push(event);
  if (state.events.length > MAX_EVENTS) state.events.shift();
  if (type === 'stranger_alert' || type === 'stranger_notification') {
    state.alerts.push(event);
    if (state.alerts.length > MAX_ALERTS) state.alerts.shift();
  }
  broadcast({ type: 'event', childId, event });
  return true;
}

function getStats() {
  const byType = {};
  for (const e of state.events) byType[e.type] = (byType[e.type] || 0) + 1;
  return {
    children: [...state.children.values()].map(c => ({
      id: c.id, name: c.name, lastSeen: c.lastSeen, online: (now() - c.lastSeen) < 60000
    })),
    totalEvents: state.events.length,
    totalAlerts: state.alerts.length,
    byType,
    recentAlerts: state.alerts.slice(-50).reverse(),
  };
}

/** Parse a limit, falling back to the default and capping the result.
 *
 * Mirrors the Rust collector: only ASCII digits are accepted, so "5.9" and
 * "12abc" fall back instead of being silently truncated by parseInt. Zero is
 * a real limit ("none") and must not be replaced by the default.
 */
function limitParam(raw, fallback, max) {
  if (typeof raw !== 'string' || !/^[0-9]+$/.test(raw)) return fallback;
  const n = Number(raw);
  if (!Number.isSafeInteger(n)) return fallback;
  return Math.min(n, max);
}

/** The last `limit` items. slice(-0) returns the whole list, so be explicit. */
function tail(list, limit) {
  return limit <= 0 ? [] : list.slice(-limit);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'OPTIONS') {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Authorization');
    res.writeHead(204);
    res.end();
    return;
  }
  res.setHeader('Access-Control-Allow-Origin', '*');

  if (url.pathname !== '/health' && !authorized(req)) {
    sendJson(res, 401, { error: 'unauthorized' });
    return;
  }

  if (url.pathname === '/health') {
    // No token or token prefix: /health is deliberately unauthenticated.
    sendJson(res, 200, { ok: true, service: 'childguard', uptime: process.uptime() });
    return;
  }

  if (url.pathname === '/api/register' && req.method === 'POST') {
    readBody(req, res, body => {
      let d;
      try { d = JSON.parse(body); } catch (e) {
        sendJson(res, 400, { error: 'invalid body' });
        return;
      }
      const name = (typeof d.name === 'string' && d.name.trim()) ? d.name.trim().slice(0, 128) : 'Child';
      const child = bumpChild(uid(), name);
      // Never echo the parent token; the client already has it.
      sendJson(res, 200, { childId: child.id });
    });
    return;
  }

  if (url.pathname === '/api/events' && req.method === 'POST') {
    readBody(req, res, body => {
      let d;
      try {
        d = JSON.parse(body);
      } catch (e) {
        console.error('[events] parse failed, bodyLen=' + body.length + ' err=' + e.message);
        sendJson(res, 400, { error: 'invalid body' });
        return;
      }
      if (typeof d.childId !== 'string' || !d.childId) {
        sendJson(res, 400, { error: 'childId required' });
        return;
      }
      // Cap before the id becomes a Map key and part of the dedupe hash.
      const childId = clip(d.childId, MAX_ID_BYTES);
      let events;
      if (Array.isArray(d.events)) events = d.events;
      else if (d.events && typeof d.events === 'object') events = [d.events];
      else {
        sendJson(res, 400, { error: 'events must be an array or object' });
        return;
      }

      // Auto-register unknown child ids so events are never dropped.
      const child = state.children.get(childId) || bumpChild(childId, 'Child');
      child.lastSeen = now();

      let received = 0;
      for (const ev of events.slice(0, MAX_EVENTS)) {
        if (!ev || typeof ev !== 'object' || Array.isArray(ev)) continue;
        if (addEvent(child.id, ev)) received += 1;
      }
      sendJson(res, 200, { ok: true, received });
    });
    return;
  }

  if (url.pathname === '/api/stats') {
    sendJson(res, 200, getStats());
    return;
  }

  if (url.pathname === '/api/alerts') {
    const limit = limitParam(url.searchParams.get('limit'), 100, MAX_ALERTS);
    sendJson(res, 200, {
      alerts: tail(state.alerts, limit).reverse(),
      total: state.alerts.length,
    });
    return;
  }

  if (url.pathname === '/api/events') {
    const limit = limitParam(url.searchParams.get('limit'), 200, MAX_EVENTS);
    sendJson(res, 200, {
      events: tail(state.events, limit).reverse(),
      total: state.events.length,
    });
    return;
  }

  if (url.pathname === '/api/export') {
    sendJson(res, 200, {
      stats: getStats(),
      events: state.events.slice(-MAX_EVENTS).reverse(),
      alerts: state.alerts.slice(-MAX_ALERTS).reverse(),
      exportedAt: now(),
    });
    return;
  }

  if (url.pathname === '/api/stream') {
    if (state.clients.size >= MAX_STREAMS) {
      sendJson(res, 503, { error: 'too many stream clients' });
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    });
    state.clients.add(res);
    res.write('data: ' + JSON.stringify({ type: 'connected', stats: getStats() }) + '\n\n');

    // Match the Rust collector's heartbeat so an idle proxy does not drop a
    // long-lived dashboard stream.
    const heartbeat = setInterval(() => {
      try { res.write(': ping\n\n'); } catch (e) { cleanup(); }
    }, SSE_HEARTBEAT_MS);
    function cleanup() {
      clearInterval(heartbeat);
      state.clients.delete(res);
    }
    req.on('close', cleanup);
    req.on('error', cleanup);
    res.on('error', cleanup);
    return;
  }

  sendJson(res, 404, { error: 'not found' });
});

server.listen(PORT, () => {
  console.log(`ChildGuard server running on http://0.0.0.0:${PORT}`);
  if (TOKEN_GENERATED) {
    console.log(`Generated parent token (set CHILDGUARD_TOKEN to choose one): ${AUTH_TOKEN}`);
  } else if (AUTH_TOKEN.length < 16) {
    console.warn(`WARNING: parent token is only ${AUTH_TOKEN.length} characters; use 16+ random characters.`);
  }
  console.log('Endpoints: /health /api/register /api/events /api/stats /api/alerts /api/export /api/stream');
});
