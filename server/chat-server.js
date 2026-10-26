// Сервер чата Bearite. Без зависимостей: node chat-server.js
// Переменные: PORT (по умолчанию 8080), CHAT_KEY (необязательный общий ключ; клиент шлёт его в заголовке X-Key).
// Сообщения хранятся только в памяти и привязаны к комнате игры (имя комнаты Photon).
const http = require('http');
const { URL } = require('url');

const PORT = +process.env.PORT || 8080;
const KEY = process.env.CHAT_KEY || '';
const KEEP = 200;            // сообщений на комнату
const ROOM_TTL = 3600e3;     // комната без активности забывается через час
const MIN_GAP = 600;         // мс между сообщениями с одного адреса
const rooms = new Map();     // имя -> {msgs:[], seq, waiters:Set, touched}
const lastSend = new Map();  // ip -> время

function room(name) {
  let r = rooms.get(name);
  if (!r) rooms.set(name, (r = { msgs: [], seq: 0, waiters: new Set(), touched: Date.now() }));
  r.touched = Date.now();
  return r;
}
const clean = (s, n) => String(s ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, n);
const json = (res, code, obj) => { const b = JSON.stringify(obj); res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' }); res.end(b); };
const slice = (r, after) => r.msgs.filter(m => m.id > after).slice(-50);

setInterval(() => { const now = Date.now(); for (const [k, r] of rooms) if (now - r.touched > ROOM_TTL && !r.waiters.size) rooms.delete(k); }, 60e3).unref();

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, X-Key' }); return res.end(); }
  if (KEY && req.headers['x-key'] !== KEY && u.pathname !== '/') return json(res, 403, { error: 'ключ' });
  if (u.pathname === '/') return json(res, 200, { ok: true, rooms: rooms.size });

  if (u.pathname === '/poll' && req.method === 'GET') {
    const name = clean(u.searchParams.get('room'), 64);
    if (!name) return json(res, 400, { error: 'нет комнаты' });
    const after = +u.searchParams.get('after') || 0;
    const wait = Math.min(25, +u.searchParams.get('wait') || 0) * 1000;
    const r = room(name);
    const now = slice(r, after);
    if (now.length || !wait) return json(res, 200, { msgs: now, last: r.seq });
    const w = { res, after };
    const t = setTimeout(() => { r.waiters.delete(w); json(res, 200, { msgs: [], last: r.seq }); }, wait);
    w.t = t;
    r.waiters.add(w);
    req.on('close', () => { clearTimeout(t); r.waiters.delete(w); });
    return;
  }

  if (u.pathname === '/send' && req.method === 'POST') {
    let body = '';
    req.on('data', c => { body += c; if (body.length > 4096) req.destroy(); });
    req.on('end', () => {
      let d; try { d = JSON.parse(body); } catch { return json(res, 400, { error: 'json' }); }
      const name = clean(d.room, 64), who = clean(d.name, 24) || 'игрок', text = clean(d.text, 200);
      if (!name || !text) return json(res, 400, { error: 'пусто' });
      const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
      const now = Date.now();
      if (now - (lastSend.get(ip) || 0) < MIN_GAP) return json(res, 429, { error: 'слишком часто' });
      lastSend.set(ip, now);
      const r = room(name);
      const m = { id: ++r.seq, name: who, text, t: now };
      r.msgs.push(m); if (r.msgs.length > KEEP) r.msgs.shift();
      for (const w of [...r.waiters]) { clearTimeout(w.t); r.waiters.delete(w); json(w.res, 200, { msgs: slice(r, w.after), last: r.seq }); }
      json(res, 200, { id: m.id });
    });
    return;
  }
  json(res, 404, { error: 'нет такого' });
}).listen(PORT, () => console.log('чат Bearite на порту ' + PORT));
