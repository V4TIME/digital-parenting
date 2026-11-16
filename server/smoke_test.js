// Regression test: an event from a device that never registered must be
// accepted, not crash the process (it used to throw TypeError).
const { spawn } = require('child_process');
const http = require('http');
const assert = require('assert');

const PORT = 38999;
const TOKEN = 'smoketoken';
const srv = spawn(process.execPath, [__dirname + '/index.js'], {
  env: { ...process.env, PORT: String(PORT), CHILDGUARD_TOKEN: TOKEN },
  stdio: 'ignore',
});

function post(path, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request({
      host: '127.0.0.1', port: PORT, path, method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
        Authorization: 'Bearer ' + TOKEN,
      },
    }, res => {
      let out = '';
      res.on('data', c => out += c);
      res.on('end', () => resolve({ code: res.statusCode, body: out }));
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

(async () => {
  await new Promise(r => setTimeout(r, 700));
  // unregistered child id: this is what crashed the server before
  const r = await post('/api/events', {
    childId: 'cg_never_registered',
    events: [{ type: 'keylog', package: 'com.whatsapp', data: 'hello', ts: 1 }],
  });
  assert.strictEqual(r.code, 200, 'event POST failed: ' + r.body);
  assert.strictEqual(JSON.parse(r.body).received, 1);

  // server must still be alive and answer a second request
  const r2 = await post('/api/events', {
    childId: 'cg_never_registered',
    events: [{ type: 'keylog', package: 'com.whatsapp', data: 'again', ts: 2 }],
  });
  assert.strictEqual(r2.code, 200, 'server died after the first event');
  console.log('SMOKE OK: unknown-child event accepted twice, server alive');
  srv.kill();
  process.exit(0);
})().catch(e => { console.error('SMOKE FAIL:', e.message); srv.kill(); process.exit(1); });
