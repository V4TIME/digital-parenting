const http = require('http');
const { spawn } = require('child_process');
const crypto = require('crypto');

const PORT = process.env.PORT || 38080;
const AUTH_TOKEN = process.env.CHILDGUARD_TOKEN || crypto.randomBytes(24).toString('hex');

const state = {
  children: new Map(),
  events: [],
  alerts: [],
  clients: new Set(),
  maxEvents: 5000,
  maxAlerts: 500,
};

function now() { return Date.now(); }
function uid() { return 'cg_' + crypto.randomBytes(8).toString('hex'); }

function broadcast(obj) {
  const payload = JSON.stringify(obj);
  for (const c of state.clients) {
    try { c.write('data: ' + payload + '\n\n'); } catch (e) {}
  }
}

function addEvent(childId, ev) {
  ev.id = uid();
  ev.ts = now();
  state.events.push(ev);
  if (state.events.length > state.maxEvents) state.events.shift();
  if (ev.type === 'stranger_alert' || ev.type === 'stranger_notification') {
    state.alerts.push(ev);
    if (state.alerts.length > state.maxAlerts) state.alerts.shift();
  }
  broadcast({ type: 'event', childId, event: ev });
  return ev;
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

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === 'OPTIONS') {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Authorization');
    res.writeHead(204);
    res.end();
    return;
  }
  res.setHeader('Access-Control-Allow-Origin', '*');

  const auth = req.headers.authorization;
  if (auth !== `Bearer ${AUTH_TOKEN}` && url.pathname !== '/health') {
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'unauthorized' }));
    return;
  }

  if (url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, token: AUTH_TOKEN.slice(0, 8) + '...', uptime: process.uptime() }));
    return;
  }

  if (url.pathname === '/api/register' && req.method === 'POST') {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      try {
        const d = JSON.parse(body);
        const child = { id: uid(), name: d.name || 'Child', token: d.token, lastSeen: now(), registeredAt: now() };
        state.children.set(child.id, child);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ childId: child.id, token: AUTH_TOKEN }));
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'invalid body' }));
      }
    });
    return;
  }

  if (url.pathname === '/api/events' && req.method === 'POST') {
    req.setEncoding('utf8');
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      let d;
      try {
        d = JSON.parse(body);
      } catch (e) {
        console.error('[events] parse failed, bodyLen=' + body.length + ' err=' + e.message);
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'invalid body', detail: e.message }));
        return;
      }
      const child = state.children.get(d.childId);
      if (!child) { res.writeHead(404, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error: 'child not found' })); return; }
      child.lastSeen = now();
      const events = Array.isArray(d.events) ? d.events : [d.events];
      for (const ev of events) addEvent(child.id, ev);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, received: events.length }));
    });
    return;
  }

  if (url.pathname === '/api/stats') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(getStats()));
    return;
  }

  if (url.pathname === '/dashboard' || url.pathname === '/dashboard.html') {
    try {
      const fs = require('fs');
      const path = require('path');
      const file = path.join(__dirname, '..', 'dashboard', 'index.html');
      const html = fs.readFileSync(file, 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
    } catch (e) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'dashboard not found' }));
    }
    return;
  }

  if (url.pathname === '/api/alerts') {
    const limit = parseInt(url.searchParams.get('limit') || '100');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ alerts: state.alerts.slice(-limit).reverse(), total: state.alerts.length }));
    return;
  }

  if (url.pathname === '/api/events') {
    const limit = parseInt(url.searchParams.get('limit') || '200');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ events: state.events.slice(-limit).reverse(), total: state.events.length }));
    return;
  }

  if (url.pathname === '/api/stream') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    });
    state.clients.add(res);
    res.write('data: ' + JSON.stringify({ type: 'connected', stats: getStats() }) + '\n\n');
    req.on('close', () => state.clients.delete(res));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'not found' }));
});

server.listen(PORT, () => {
  console.log(`ChildGuard server running on http://0.0.0.0:${PORT}`);
  console.log(`Parent token: ${AUTH_TOKEN}`);
  console.log(`Dashboard: http://0.0.0.0:${PORT}/dashboard.html`);
});