// Сервер мультиплеера Bearite. Без зависимостей: node bearite-server.js
// Переменные: PORT (8080), SERVER_NAME (имя в списке серверов), MOTD (описание), MAX_ROOMS (50), KEY (общий ключ, необязательно; заголовок X-Key).
// Всё хранится в памяти. Протокол — обычный HTTP + JSON, клиент лаунчера держит длинный опрос /poll.
//
//  GET  /info                         -> {name,motd,rooms,players,v}
//  GET  /rooms                        -> {rooms:[{id,name,players,max,host,game}]}
//  POST /create {name,max,nick,cid,scene}  -> {room,id}   max 0 = без ограничения (до 255)
//  POST /join   {room,nick,cid}       -> {ok,room,players:[...]}
//  POST /leave  {room,cid}
//  POST /send   {room,cid,k,text|data} k = chat (по умолчанию) | pos | mg | любое
//  GET  /poll?room=&cid=&after=&wait=20&kind=  -> {last,msgs:[{id,k,cid,name,text,data}],players:[...]}
//  GET  /players?room=
const http = require('http');
const { URL } = require('url');

const PORT = +process.env.PORT || 8080;
const NAME = process.env.SERVER_NAME || 'Bearite сервер';
const MOTD = process.env.MOTD || '';
const KEY = process.env.KEY || '';
const MAX_ROOMS = +process.env.MAX_ROOMS || 50;
const HARD_MAX = 255;
const KEEP = 300;           // событий на комнату
const PLAYER_TTL = 25e3;    // игрок без опроса дольше этого времени выпадает из комнаты
const EMPTY_TTL = 60e3;     // пустая комната удаляется
const CHAT_GAP = 500;       // мс между чат-сообщениями одного игрока

const rooms = new Map();    // id -> room
function newCode() {            // шестизначный код комнаты, как в оригинале (например 788006)
  for (let i = 0; i < 50; i++) { const c = String(100000 + Math.floor(Math.random() * 900000)); if (!rooms.has(c)) return c; }
  return String(Date.now()).slice(-8);
}

const clean = (s, n) => String(s ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, n);
const json = (res, code, obj) => {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(obj));
};
const body = (req) => new Promise((ok) => {
  let b = ''; req.on('data', (c) => { b += c; if (b.length > 20000) req.destroy(); });
  req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch { ok({}); } });
  req.on('error', () => ok({}));
});

const publicPlayers = (r) => [...r.players.values()].map((p) => ({ cid: p.cid, nick: p.nick, actor: p.actor, host: p.cid === r.host }));
const summary = (r) => ({ id: r.id, name: r.name, players: r.players.size, max: r.max, host: (r.players.get(r.host) || {}).nick || '', game: r.game || '' });

function wake(r) { for (const w of [...r.waiters]) w(); }
function push(r, ev) {
  ev.id = ++r.seq; r.events.push(ev);
  if (r.events.length > KEEP) r.events.splice(0, r.events.length - KEEP);
  wake(r);
}
function dropPlayer(r, cid, why) {
  const p = r.players.get(cid); if (!p) return;
  r.players.delete(cid);
  if (r.host === cid) {            // новый хост — самый ранний из оставшихся
    const next = [...r.players.values()].sort((a, b) => a.actor - b.actor)[0];
    r.host = next ? next.cid : '';
  }
  push(r, { k: 'sys', text: `${p.nick} ${why || 'вышел'}`, cid: '', name: '' });
  r.empty = r.players.size ? 0 : Date.now();
}

setInterval(() => {
  const now = Date.now();
  for (const [id, r] of rooms) {
    for (const p of [...r.players.values()]) if (now - p.seen > PLAYER_TTL) dropPlayer(r, p.cid, 'отключился');
    if (!r.players.size && r.empty && now - r.empty > EMPTY_TTL && !r.waiters.size) rooms.delete(id);
  }
}, 5e3).unref();

function uniqueNick(r, nick, cid) {
  let n = nick || 'игрок', i = 2;
  const taken = (x) => [...r.players.values()].some((p) => p.cid !== cid && p.nick === x);
  while (taken(n)) n = `${nick || 'игрок'} ${i++}`;
  return n;
}

function addPlayer(r, cid, nick) {
  let p = r.players.get(cid);
  if (p) { p.seen = Date.now(); return { p, fresh: false }; }
  p = { cid, nick: uniqueNick(r, nick, cid), actor: ++r.actors, seen: Date.now(), lastChat: 0 };
  r.players.set(cid, p);
  if (!r.host || !r.players.has(r.host)) r.host = cid;
  r.empty = 0;
  push(r, { k: 'sys', text: `${p.nick} зашёл`, cid: '', name: '' });
  return { p, fresh: true };
}

http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, X-Key' }); return res.end(); }
  if (KEY && req.headers['x-key'] !== KEY && u.pathname !== '/') return json(res, 403, { error: 'ключ' });
  const p = u.pathname;

  if (p === '/') { res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end(`${NAME}: сервер Bearite работает\n`); }

  if (p === '/info') {
    let pl = 0; for (const r of rooms.values()) pl += r.players.size;
    return json(res, 200, { name: NAME, motd: MOTD, rooms: rooms.size, players: pl, v: 1, key: !!KEY });
  }

  if (p === '/rooms') return json(res, 200, { rooms: [...rooms.values()].map(summary).sort((a, b) => b.players - a.players) });

  if (p === '/players') { const r = rooms.get(u.searchParams.get('room')); return json(res, r ? 200 : 404, { players: r ? publicPlayers(r) : [] }); }

  if (p === '/create' && req.method === 'POST') {
    const b = await body(req);
    if (rooms.size >= MAX_ROOMS) return json(res, 429, { error: 'слишком много комнат' });
    const cid = clean(b.cid, 40); if (!cid) return json(res, 400, { error: 'cid' });
    let max = Math.floor(+b.max || 0); if (max < 0) max = 0; if (max > HARD_MAX) max = HARD_MAX;
    const id = newCode();
    const r = { id, name: clean(b.name, 32) || `Комната ${id}`, max, scene: clean(b.scene, 40), game: '', host: '', players: new Map(), events: [], seq: 0, actors: 0, waiters: new Set(), empty: Date.now() };
    rooms.set(id, r);
    const { p: pl } = addPlayer(r, cid, clean(b.nick, 24));
    return json(res, 200, { ok: true, room: id, id, nick: pl.nick, actor: pl.actor, players: publicPlayers(r), max: r.max, name: r.name });
  }

  if (p === '/join' && req.method === 'POST') {
    const b = await body(req);
    const r = rooms.get(clean(b.room, 20)); if (!r) return json(res, 404, { error: 'комнаты нет' });
    const cid = clean(b.cid, 40); if (!cid) return json(res, 400, { error: 'cid' });
    if (!r.players.has(cid) && r.max > 0 && r.players.size >= r.max) return json(res, 409, { error: 'комната заполнена' });
    const { p: pl } = addPlayer(r, cid, clean(b.nick, 24));
    return json(res, 200, { ok: true, room: r.id, name: r.name, nick: pl.nick, actor: pl.actor, players: publicPlayers(r), max: r.max, last: r.seq });
  }

  if (p === '/leave' && req.method === 'POST') {
    const b = await body(req); const r = rooms.get(clean(b.room, 20));
    if (r) dropPlayer(r, clean(b.cid, 40));
    return json(res, 200, { ok: true });
  }

  if (p === '/send' && req.method === 'POST') {
    const b = await body(req);
    const r = rooms.get(clean(b.room, 20)); if (!r) return json(res, 404, { error: 'комнаты нет' });
    const pl = r.players.get(clean(b.cid, 40)); if (!pl) return json(res, 403, { error: 'ты не в комнате' });
    pl.seen = Date.now();
    const k = clean(b.k, 12) || 'chat';
    if (k === 'chat') {
      const now = Date.now(); if (now - pl.lastChat < CHAT_GAP) return json(res, 429, { error: 'часто' });
      pl.lastChat = now;
      const text = clean(b.text, 200); if (!text) return json(res, 400, { error: 'пусто' });
      push(r, { k, cid: pl.cid, name: pl.nick, text });
    } else {
      if (k === 'mg' && b.data && typeof b.data.g === 'string' && b.data.k === 'start') r.game = clean(b.data.g, 12);
      if (k === 'mg' && b.data && (b.data.k === 'end' || b.data.k === 'stop')) r.game = '';
      const data = JSON.stringify(b.data ?? null); if (data.length > 4000) return json(res, 413, { error: 'много' });
      push(r, { k, cid: pl.cid, name: pl.nick, actor: pl.actor, data: b.data ?? null });
    }
    return json(res, 200, { ok: true });
  }

  if (p === '/poll') {
    const r = rooms.get(clean(u.searchParams.get('room'), 20)); if (!r) return json(res, 404, { error: 'комнаты нет' });
    const cid = clean(u.searchParams.get('cid'), 40);
    const pl = r.players.get(cid); if (!pl) return json(res, 403, { error: 'ты не в комнате' });
    const after = +u.searchParams.get('after') || 0;
    const kind = clean(u.searchParams.get('kind'), 12);
    const wait = Math.min(+u.searchParams.get('wait') || 0, 25) * 1000;
    const collect = () => r.events.filter((e) => e.id > after && (!kind || e.k === kind || e.k === 'sys')).slice(-120);
    const answer = () => { pl.seen = Date.now(); json(res, 200, { last: r.seq, msgs: collect(), players: publicPlayers(r), host: r.host, max: r.max, name: r.name }); };
    pl.seen = Date.now();
    if (collect().length || !wait) return answer();
    let done = false;
    const fin = () => { if (done) return; done = true; clearTimeout(t); r.waiters.delete(fin); answer(); };
    const t = setTimeout(fin, wait);
    r.waiters.add(fin);
    req.on('close', () => { if (!done) { done = true; clearTimeout(t); r.waiters.delete(fin); } });
    return;
  }

  json(res, 404, { error: 'нет такого пути' });
}).listen(PORT, () => console.log(`${NAME}: порт ${PORT}`));
