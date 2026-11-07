'use strict';
/*
 * Yoru: общий прогресс просмотра для веба и Android TV.
 *
 * Подключение в server.js (CommonJS или ESM — файл .cjs импортируется и так, и так):
 *
 *   const createSync = require('./yoru-sync.cjs');        // ESM: import createSync from './yoru-sync.cjs';
 *   const sync = createSync({
 *     dataDir: path.join(__dirname, 'data'),               // рядом с users.json
 *     appName: 'Yoru',                                      // User-Agent для Shikimori (= названию приложения)
 *     getSessionUser: (req) => {                            // вход через веб: вернуть { id, nickname } или null
 *       const u = currentUser(req);                         // ← ваша функция сессии
 *       return u ? { id: u.shikiId, nickname: u.nickname } : null;
 *     },
 *     isAllowed: (user) => isAllowedNick(user.nickname),    // ← ваш список доступа (по умолчанию пускаем всех)
 *   });
 *
 *   // в обработчике запросов, до остальных маршрутов:
 *   if (await sync.handle(req, res)) return;
 *
 * API:
 *   GET  /api/sync?since=<ms>  → { now, progress: { [episodeId]: {t,d,at,sid,mal} }, history: [...] }
 *   POST /api/sync             ← { progress: {...}, history: [...] }  → { ok, now }
 *
 * Авторизация: сессия веба (getSessionUser) или заголовок
 * `Authorization: Bearer <access_token Shikimori>` — так входит телевизор. Токен проверяется через
 * /api/users/whoami (с кэшем на 10 минут), дальше — тот же список доступа, что у веба.
 *
 * Данные: data/sync/<shikimoriUserId>.json (права 600). Конфликты: побеждает запись с бо́льшим `at`.
 */

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const crypto = require('crypto');

const MAX_HISTORY = 60;
const MAX_PROGRESS = 5000;
const MAX_BODY = 512 * 1024;

module.exports = function createSync(opts = {}) {
  const dir = path.join(opts.dataDir || path.join(process.cwd(), 'data'), 'sync');
  const appName = opts.appName || 'Yoru';
  const shikiOrigin = opts.shikiOrigin || 'https://shikimori.io';
  const getSessionUser = opts.getSessionUser || (() => null);
  const isAllowed = opts.isAllowed || (() => true);
  const tokenCache = new Map(); // sha256(token) -> { user, at }
  const locks = new Map();      // userId -> Promise (последовательная запись файла)

  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });

  // ---------------------------------------------------------------- авторизация
  async function userFromToken(token) {
    const key = crypto.createHash('sha256').update(token).digest('hex');
    const hit = tokenCache.get(key);
    if (hit && Date.now() - hit.at < 10 * 60e3) return hit.user;
    let r;
    try {
      r = await fetch(shikiOrigin + '/api/users/whoami', {
        headers: { Authorization: 'Bearer ' + token, 'User-Agent': appName, Accept: 'application/json' },
        signal: AbortSignal.timeout(10000),
      });
    } catch (e) {
      // Shikimori недоступен — доверяем недавней проверке
      if (hit) return hit.user;
      console.warn('[yoru-sync] Shikimori недоступен с сервера:', e && e.message);
      return { reason: 'Сервер Yoru не смог связаться с Shikimori (' + (e && e.message || 'ошибка сети') + ')' };
    }
    if (!r.ok) {
      tokenCache.delete(key);
      console.warn('[yoru-sync] whoami ответил', r.status);
      return { reason: 'Shikimori не подтвердил токен (HTTP ' + r.status + ')' };
    }
    const j = await r.json().catch(() => null);
    if (!j || !j.id) return { reason: 'Shikimori вернул пустой ответ на whoami' };
    const user = { id: Number(j.id), nickname: j.nickname || '' };
    tokenCache.set(key, { user, at: Date.now() });
    if (tokenCache.size > 500) tokenCache.delete(tokenCache.keys().next().value);
    return user;
  }

  async function authUser(req) {
    const h = req.headers['authorization'] || '';
    const m = /^Bearer\s+(.+)$/i.exec(h);
    const user = m ? await userFromToken(m[1].trim()) : await Promise.resolve(getSessionUser(req));
    if (!user || !user.id) {
      return { error: 401, reason: (user && user.reason) || (m ? 'Токен не принят' : 'Нет входа в веб-сессию') };
    }
    if (!(await Promise.resolve(isAllowed(user)))) return { error: 403, reason: 'Ника ' + user.nickname + ' нет в списке доступа' };
    return { user };
  }

  // ---------------------------------------------------------------- хранилище
  const file = (uid) => path.join(dir, String(Number(uid)) + '.json');

  async function load(uid) {
    try {
      const j = JSON.parse(await fsp.readFile(file(uid), 'utf8'));
      return { progress: j.progress || {}, history: Array.isArray(j.history) ? j.history : [] };
    } catch {
      return { progress: {}, history: [] };
    }
  }

  async function save(uid, data) {
    const f = file(uid);
    const tmp = f + '.' + process.pid + '.tmp';
    await fsp.writeFile(tmp, JSON.stringify(data), { mode: 0o600 });
    await fsp.rename(tmp, f);
  }

  function withLock(uid, fn) {
    const prev = locks.get(uid) || Promise.resolve();
    const next = prev.then(fn, fn);
    locks.set(uid, next.catch(() => {}));
    return next;
  }

  // ---------------------------------------------------------------- слияние
  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);

  function cleanProgress(p) {
    if (!p || typeof p !== 'object') return null;
    const t = num(p.t), d = num(p.d), at = num(p.at);
    if (d <= 0 || t < 0 || at <= 0) return null;
    const out = { t: Math.round(t), d: Math.round(d), at };
    if (p.sid) out.sid = num(p.sid);
    if (p.mal) out.mal = num(p.mal);
    if (num(p.tr) > 0) out.tr = num(p.tr);          // id перевода Anime365, которым смотрели серию
    return out;
  }

  function cleanHistory(h) {
    if (!h || typeof h !== 'object' || !h.eid || !num(h.at)) return null;
    const s = (v, n) => (v == null ? undefined : String(v).slice(0, n));
    const out = {
      eid: num(h.eid), sid: num(h.sid) || undefined, mal: num(h.mal) || undefined,
      n: h.n != null ? num(h.n) : undefined, ep: s(h.ep, 80), title: s(h.title, 200), poster: s(h.poster, 500),
      at: num(h.at),
      // перевод, которым смотрели тайтл последним (поля Anime365 typeKind / typeLang / authorsSummary)
      tr: num(h.tr) || undefined, trKind: s(h.trKind, 20), trLang: s(h.trLang, 10), trAuthor: s(h.trAuthor, 200),
      // убрано из «Продолжить просмотр»: запись остаётся как метка, чтобы другие устройства тоже убрали
      del: h.del === true ? true : undefined,
    };
    // пустые поля не должны затирать известные (например, mal из записи другого устройства)
    for (const k of Object.keys(out)) if (out[k] === undefined) delete out[k];
    return out;
  }

  function merge(cur, body, now) {
    let changed = false;
    const inP = body && body.progress && typeof body.progress === 'object' ? body.progress : {};
    for (const [eid, raw] of Object.entries(inP)) {
      const p = cleanProgress(raw);
      if (!p || !num(eid)) continue;
      const old = cur.progress[eid];
      if (!old || p.at > old.at) { cur.progress[eid] = { ...old, ...p, st: now }; changed = true; }
    }
    const keys = Object.keys(cur.progress);
    if (keys.length > MAX_PROGRESS) {
      keys.sort((a, b) => cur.progress[a].at - cur.progress[b].at)
        .slice(0, keys.length - MAX_PROGRESS).forEach((k) => delete cur.progress[k]);
    }
    const inH = body && Array.isArray(body.history) ? body.history : [];
    for (const raw of inH) {
      const h = cleanHistory(raw);
      if (!h) continue;
      // одна запись на тайтл: по Anime365 series id, иначе по MAL id
      const same = (x) => (h.sid && x.sid === h.sid) || (h.mal && x.mal === h.mal);
      const old = cur.history.find(same);
      if (old && old.at >= h.at) continue;
      cur.history = cur.history.filter((x) => !same(x));
      // новая обычная запись снимает старую метку удаления
      const next = { ...old, ...h, st: now };
      if (!h.del) delete next.del;
      cur.history.push(next);
      changed = true;
    }
    cur.history.sort((a, b) => b.at - a.at);
    if (cur.history.length > MAX_HISTORY) cur.history.length = MAX_HISTORY;
    return changed;
  }

  // ---------------------------------------------------------------- HTTP
  function send(res, code, obj) {
    const body = JSON.stringify(obj);
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(body);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0; const chunks = [];
      req.on('data', (c) => {
        size += c.length;
        if (size > MAX_BODY) { reject(new Error('too large')); req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://x');
    if (url.pathname !== '/api/sync') return false;
    try {
      const a = await authUser(req);
      if (a.error) { send(res, a.error, { error: a.reason || (a.error === 401 ? 'Нужен вход' : 'Нет доступа'), module: 'yoru-sync' }); return true; }
      const uid = a.user.id;
      if (req.method === 'GET') {
        const since = num(url.searchParams.get('since'));
        const cur = await load(uid);
        const now = Date.now();
        const progress = {};
        for (const [k, v] of Object.entries(cur.progress)) if (!since || num(v.st) > since) progress[k] = v;
        const history = since ? cur.history.filter((h) => num(h.st) > since) : cur.history;
        send(res, 200, { now, progress, history });
        return true;
      }
      if (req.method === 'POST' || req.method === 'PUT') {
        let body;
        try { body = JSON.parse(await readBody(req)); } catch { send(res, 400, { error: 'Bad JSON' }); return true; }
        const now = Date.now();
        await withLock(uid, async () => {
          const cur = await load(uid);
          if (merge(cur, body, now)) await save(uid, cur);
        });
        send(res, 200, { ok: true, now });
        return true;
      }
      send(res, 405, { error: 'Method not allowed' });
      return true;
    } catch (e) {
      send(res, 500, { error: String(e && e.message || e) });
      return true;
    }
  }

  return { handle, merge, load };
};
