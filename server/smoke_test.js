// Regression tests for the legacy Node collector.
//
// Covers the crash that used to happen on an unregistered child, plus the
// security parity fixes: no token in /health, /api/register, or startup logs;
// constant-time auth; body/child caps; idempotent retries; timestamp clamping.
const { spawn } = require('child_process');
const http = require('http');
const assert = require('assert');

const PORT = 38999;
const TOKEN = 'smoketoken';

const srv = spawn(process.execPath, [__dirname + '/index.js'], {
  env: {
    ...process.env,
    PORT: String(PORT),
    CHILDGUARD_TOKEN: TOKEN,
    CHILDGUARD_MAX_STREAMS: '1', // exercise the cap with two connections
    CHILDGUARD_MAX_CHILDREN: '2', // exercise LRU eviction with three children
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let serverOutput = '';
srv.stdout.on('data', c => { serverOutput += c.toString(); });
srv.stderr.on('data', c => { serverOutput += c.toString(); });

function request(method, path, body, token = TOKEN) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? null : (typeof body === 'string' ? body : JSON.stringify(body));
    const headers = { Authorization: 'Bearer ' + token };
    if (data !== null) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(data);
    }
    const req = http.request({
      host: '127.0.0.1', port: PORT, path, method, headers,
    }, res => {
      let out = '';
      res.on('data', c => out += c);
      res.on('end', () => resolve({ code: res.statusCode, body: out }));
    });
    req.on('error', reject);
    if (data !== null) req.write(data);
    req.end();
  });
}

// Open an SSE stream and keep the request alive so it occupies a slot.
function openStream() {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1', port: PORT, path: '/api/stream', method: 'GET',
      headers: { Authorization: 'Bearer ' + TOKEN },
    }, res => resolve({ req, res, code: res.statusCode }));
    req.on('error', reject);
    req.end();
  });
}

(async () => {
  await new Promise(r => setTimeout(r, 700));

  // 1. /health is unauthenticated but must not disclose the token or a prefix.
  const health = await request('GET', '/health', undefined, '');
  assert.strictEqual(health.code, 200, 'health failed: ' + health.body);
  assert.strictEqual(JSON.parse(health.body).ok, true);
  assert.ok(!health.body.includes(TOKEN), '/health leaked the token');
  assert.ok(!Object.prototype.hasOwnProperty.call(JSON.parse(health.body), 'token'),
    '/health returned a token field');

  // 2. Register must not echo the token.
  const reg = await request('POST', '/api/register', { name: 'Eli' });
  assert.strictEqual(reg.code, 200, 'register failed: ' + reg.body);
  assert.ok(JSON.parse(reg.body).childId, 'register did not return childId');
  assert.ok(!reg.body.includes(TOKEN), '/api/register leaked the token');

  // 3. Wrong bearer is rejected.
  const bad = await request('POST', '/api/events', { childId: 'x', events: [] }, 'wrong');
  assert.strictEqual(bad.code, 401, 'wrong token was not rejected');

  // 3b. The scheme is case-insensitive per RFC 7235. The old Node check
  //     compared the whole header against "Bearer <token>", so "bearer ..."
  //     and "BEARER ..." were rejected even though they are valid requests.
  for (const scheme of ['bearer', 'BEARER', 'BeArEr']) {
    const ok = await new Promise((resolve, reject) => {
      const data = JSON.stringify({ childId: 'cg_never_registered', events: [] });
      const req = http.request({
        host: '127.0.0.1', port: PORT, path: '/api/events', method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(data),
          Authorization: `${scheme} ${TOKEN}`,
        },
      }, res => { res.resume(); resolve(res.statusCode); });
      req.on('error', reject);
      req.end(data);   // Content-Length is set, so the body must be sent
    });
    assert.strictEqual(ok, 200, `scheme ${scheme} was rejected`);
  }
  // A scheme that is not bearer is still rejected.
  const notBearer = await new Promise((resolve, reject) => {
    const data = JSON.stringify({ childId: 'cg_never_registered', events: [] });
    const req = http.request({
      host: '127.0.0.1', port: PORT, path: '/api/events', method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
        Authorization: `Basic ${TOKEN}`,
      },
    }, res => { res.resume(); resolve(res.statusCode); });
    req.on('error', reject);
    req.end(data);
  });
  assert.strictEqual(notBearer, 401, 'Basic scheme was accepted');

  // 4. Missing childId is a client error, not a stored event.
  const noChild = await request('POST', '/api/events', { events: [] });
  assert.strictEqual(noChild.code, 400, 'missing childId should be 400');

  // 5. An event from a device that never registered must be accepted (the
  //    original crash), and an identical retry must not inflate counts.
  const payload = {
    childId: 'cg_never_registered',
    events: [{ type: 'keylog', package: 'com.whatsapp', data: 'hello', ts: 1234 }],
  };
  const r1 = await request('POST', '/api/events', payload);
  assert.strictEqual(r1.code, 200, 'event POST failed: ' + r1.body);
  assert.strictEqual(JSON.parse(r1.body).received, 1);

  const r2 = await request('POST', '/api/events', payload);
  assert.strictEqual(r2.code, 200, 'server died after the first event');
  assert.strictEqual(JSON.parse(r2.body).received, 0, 'retry was counted twice');

  const list = await request('GET', '/api/events?limit=10');
  const stored = JSON.parse(list.body).events;
  assert.strictEqual(stored.length, 1, 'retry inflated the event list');
  assert.strictEqual(stored[0].data, 'hello');
  assert.strictEqual(stored[0].ts, 1234, 'device timestamp was overwritten');

  // 5b. `limit` matches the Rust collector: 0 means none, and a value that
  //     is not plain digits falls back to the default instead of parseInt
  //     silently truncating "5.9" to 5. slice(-0) used to return everything.
  const none = JSON.parse((await request('GET', '/api/events?limit=0')).body);
  assert.strictEqual(none.events.length, 0, 'limit=0 must return no events');
  const junkLimit = JSON.parse((await request('GET', '/api/events?limit=5.9')).body);
  assert.strictEqual(junkLimit.events.length, 1, 'a non-integer limit must fall back');
  const one = JSON.parse((await request('GET', '/api/events?limit=1')).body);
  assert.strictEqual(one.events.length, 1, 'a valid limit must still work');

  // 6. Oversized bodies are rejected instead of buffered without bound.
  const huge = { childId: 'x', events: [], pad: 'a'.repeat(2 * 1024 * 1024 + 1) };
  const big = await request('POST', '/api/events', huge);
  assert.strictEqual(big.code, 413, 'oversized body should be 413');

  // 7. Still alive, and the total did not change.
  const stats = await request('GET', '/api/stats');
  assert.strictEqual(stats.code, 200, 'server died after oversized request');
  assert.strictEqual(JSON.parse(stats.body).totalEvents, 1);

  // 7b. A null or blank device timestamp (Number(null) === 0) must not be
  //     stored at epoch 0; that would corrupt ordering and screen time.
  const nullTs = await request('POST', '/api/events', {
    childId: 'cg_null_ts',
    events: [
      { type: 'keylog', package: 'p', data: 'null-ts', ts: null },
      { type: 'keylog', package: 'p', data: 'blank-ts', ts: '' },
    ],
  });
  assert.strictEqual(nullTs.code, 200, 'null-ts batch failed: ' + nullTs.body);
  const allEvents = JSON.parse((await request('GET', '/api/events?limit=20')).body).events;
  const coerced = allEvents.filter(e => e.data === 'null-ts' || e.data === 'blank-ts');
  assert.strictEqual(coerced.length, 2, 'null/blank ts events were dropped');
  for (const e of coerced) {
    assert.ok(e.ts > 1_000_000_000_000, 'null/blank ts stored at epoch 0: ' + e.ts);
  }

  // 7c. With a two-child cap, the least-recently-seen child is evicted, not
  //     the first inserted (which could be a long-lived active child).
  const regA = JSON.parse((await request('POST', '/api/register', { name: 'A' })).body).childId;
  await new Promise(r => setTimeout(r, 5));
  const regB = JSON.parse((await request('POST', '/api/register', { name: 'B' })).body).childId;
  await new Promise(r => setTimeout(r, 5));
  // Touch A so its lastSeen is newer than B's.
  await request('POST', '/api/events', {
    childId: regA,
    events: [{ type: 'keylog', package: 'p', data: 'touch-a', ts: Date.now() }],
  });
  await new Promise(r => setTimeout(r, 5));
  const regC = JSON.parse((await request('POST', '/api/register', { name: 'C' })).body).childId;
  const ids = JSON.parse((await request('GET', '/api/stats')).body).children.map(c => c.id);
  assert.ok(ids.includes(regA), 'the recently-seen child was evicted');
  assert.ok(ids.includes(regC), 'the new child was not added');
  assert.ok(!ids.includes(regB), 'the stale child was not evicted');

  // 7d. Oversized field values are clipped before storage. Without this,
  //     5,000 events (or 2,000 children) of near-2 MiB strings pin gigabytes
  //     of RSS; /api/export then allocates that again.
  const bigBatch = {
    childId: 'c'.repeat(10_000),
    events: [{
      type: 't'.repeat(5_000),
      package: 'p'.repeat(5_000),
      data: 'é'.repeat(10_000),
      ts: Date.now(),
    }],
  };
  const bigFields = await request('POST', '/api/events', bigBatch);
  assert.strictEqual(bigFields.code, 200, 'oversized fields rejected: ' + bigFields.body);
  const clipped = JSON.parse((await request('GET', '/api/events?limit=5')).body)
    .events.find(e => e.data.startsWith('é'));
  assert.ok(clipped, 'clipped event was not stored');
  assert.ok(Buffer.byteLength(clipped.data, 'utf8') <= 8 * 1024,
    'data kept ' + Buffer.byteLength(clipped.data, 'utf8') + ' bytes');
  assert.ok(Buffer.byteLength(clipped.type, 'utf8') <= 64,
    'type kept ' + Buffer.byteLength(clipped.type, 'utf8') + ' bytes');
  assert.ok(Buffer.byteLength(clipped.package, 'utf8') <= 128,
    'package kept ' + Buffer.byteLength(clipped.package, 'utf8') + ' bytes');
  assert.ok(Buffer.byteLength(clipped.childId, 'utf8') <= 128,
    'childId kept ' + Buffer.byteLength(clipped.childId, 'utf8') + ' bytes');
  const retry = await request('POST', '/api/events', bigBatch);
  assert.strictEqual(JSON.parse(retry.body).received, 0,
    'clipped retry was counted twice');

  // 7e. Registration is idempotent when the client supplies an id, so a lost
  //     response (the app retries) does not create a second child.
  const stableName = { childId: 'cg_stable_device', name: 'Stable' };
  const stable1 = await request('POST', '/api/register', stableName);
  const stable2 = await request('POST', '/api/register', stableName);
  assert.strictEqual(JSON.parse(stable1.body).childId, 'cg_stable_device');
  assert.strictEqual(JSON.parse(stable2.body).childId, 'cg_stable_device');
  const stableKids = JSON.parse((await request('GET', '/api/stats')).body)
    .children.filter(c => c.id === 'cg_stable_device');
  assert.strictEqual(stableKids.length, 1, 'retry created a duplicate child');

  // 8. A user-supplied token must never be printed at startup.
  assert.ok(!serverOutput.includes(TOKEN), 'startup log leaked the user-supplied token');

  // 9. SSE clients are capped (bounded memory) and the slot is released when
  //    a client disconnects. Without this, one stuck dashboard tab could pin
  //    an unbounded number of listeners.
  const s1 = await openStream();
  assert.strictEqual(s1.code, 200, 'first stream should connect');
  const s2 = await openStream();
  assert.strictEqual(s2.code, 503, 'stream cap was not enforced');
  s2.res.resume();
  s1.req.destroy();
  await new Promise(r => setTimeout(r, 200));
  const s3 = await openStream();
  assert.strictEqual(s3.code, 200, 'stream slot was not released on disconnect');
  s3.req.destroy();

  console.log('SMOKE OK: auth, token non-disclosure, dedupe, caps, limit parsing, ts coercion, LRU eviction, field clipping, stream limit, and liveness');
  srv.kill();
  process.exit(0);
})().catch(e => {
  console.error('SMOKE FAIL:', e.message);
  srv.kill();
  process.exit(1);
});
