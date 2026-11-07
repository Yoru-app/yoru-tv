/*
 * Yoru: общий прогресс просмотра — клиентская часть для веб-версии.
 * Работает в паре с server/yoru-sync.cjs (маршрут /api/sync). Веб входит своей обычной сессией.
 *
 * Подключение (после основного скрипта приложения):
 *   <script src="/yoru-sync-web.js"></script>
 *   YoruSync.init({
 *     userId: currentUserId,          // id пользователя Shikimori (тот же, что в ключах yoru.<id>.*)
 *     // адаптеры к хранилищу веба; по умолчанию — формат localStorage из Yoru:
 *     //   yoru.<id>.progress = { [episodeId]: { t, d, at } }   (t, d — секунды)
 *     //   yoru.<id>.history  = [ { sid, eid, ep, series: { id, title, titles, posterUrl… }, mal?, at } ]
 *   });
 *
 * Где вызывать:
 *   - при сохранении прогресса:   YoruSync.noteProgress(eid, t, d, { sid, mal })
 *   - при записи в историю:       YoruSync.noteHistory({ sid, eid, mal, n, ep, title, poster, at })
 *   - перед продолжением серии:   await YoruSync.pull()   (тогда в progress окажется место с телевизора)
 *   - пауза / уход со страницы:   YoruSync.flush()        (на pagehide срабатывает само)
 */
(function () {
  'use strict';

  const S = {
    userId: null,
    prefix: 'yoru.',
    endpoint: '/api/sync',
    pendingP: {},
    pendingH: {},
    timer: 0,
    sinceKey: 'syncSince',
  };

  const key = (k) => S.prefix + (S.userId != null ? S.userId + '.' : '') + k;
  const get = (k, d) => { try { const v = localStorage.getItem(key(k)); return v == null ? d : JSON.parse(v); } catch { return d; } };
  const set = (k, v) => { try { localStorage.setItem(key(k), JSON.stringify(v)); } catch { /* нет места */ } };

  // ---------- адаптеры к хранилищу веба (можно переопределить в init) ----------
  const adapters = {
    readProgress: () => get('progress', {}),
    writeProgress: (p) => set('progress', p),
    readHistory: () => get('history', []),
    writeHistory: (h) => set('history', h),
    /** запись истории с сервера → формат веба */
    historyFromServer: (h, old) => ({
      ...(old || {}),
      sid: h.sid, eid: h.eid, ep: h.ep, mal: h.mal, at: h.at,
      series: { ...((old && old.series) || {}), id: h.sid, title: h.title, titles: { ru: h.title }, posterUrl: h.poster, posterUrlSmall: h.poster },
    }),
    sameTitle: (a, b) => (a.sid && b.sid && a.sid === b.sid) || (a.mal && b.mal && a.mal === b.mal),
  };

  function schedule(ms) {
    if (S.timer && ms >= 1000) return; // во время просмотра не откладываем отправку бесконечно
    clearTimeout(S.timer);
    S.timer = setTimeout(() => { S.timer = 0; push(); }, ms);
  }

  async function push() {
    const progress = S.pendingP, history = Object.values(S.pendingH);
    if (!Object.keys(progress).length && !history.length) return true;
    S.pendingP = {}; S.pendingH = {};
    try {
      const r = await fetch(S.endpoint, {
        method: 'POST', credentials: 'same-origin', keepalive: true,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ progress, history }),
      });
      if (!r.ok) throw new Error(String(r.status));
      return true;
    } catch {
      // вернуть в очередь, если за это время не появилось новее
      for (const [k, v] of Object.entries(progress)) if (!S.pendingP[k] || S.pendingP[k].at < v.at) S.pendingP[k] = v;
      for (const h of history) { const k = h.sid || h.mal; if (!S.pendingH[k] || S.pendingH[k].at < h.at) S.pendingH[k] = h; }
      schedule(60000);
      return false;
    }
  }

  async function pull() {
    const since = get(S.sinceKey, 0);
    let j;
    try {
      const r = await fetch(S.endpoint + '?since=' + since, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (!r.ok) return false;
      j = await r.json();
    } catch { return false; }

    const local = adapters.readProgress();
    let changed = false;
    for (const [eid, p] of Object.entries(j.progress || {})) {
      const cur = local[eid];
      if (!cur || !cur.at || p.at > cur.at) { local[eid] = { t: p.t, d: p.d, at: p.at }; changed = true; }
    }
    if (changed) adapters.writeProgress(local);

    if ((j.history || []).length) {
      let hist = adapters.readHistory();
      for (const h of j.history) {
        const old = hist.find((x) => adapters.sameTitle(x, h));
        if (old && old.at >= h.at) continue;
        hist = hist.filter((x) => !adapters.sameTitle(x, h));
        hist.push(adapters.historyFromServer(h, old));
      }
      hist.sort((a, b) => b.at - a.at);
      adapters.writeHistory(hist.slice(0, 40));
    }
    if (j.now) set(S.sinceKey, j.now);
    return true;
  }

  window.YoruSync = {
    init(opts = {}) {
      Object.assign(S, opts.options || {});
      if ('userId' in opts) S.userId = opts.userId;
      if (opts.prefix) S.prefix = opts.prefix;
      if (opts.endpoint) S.endpoint = opts.endpoint;
      for (const k of Object.keys(adapters)) if (typeof opts[k] === 'function') adapters[k] = opts[k];
      addEventListener('pagehide', () => { if (S.timer) { clearTimeout(S.timer); S.timer = 0; push(); } });
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') push();
        else pull();
      });
      return pull();
    },
    /** t и d — секунды, как в progress веба. */
    noteProgress(eid, t, d, extra = {}) {
      if (!eid || !d) return;
      S.pendingP[eid] = { t: Math.round(t), d: Math.round(d), at: Date.now(), ...extra };
      schedule(8000);
    },
    noteHistory(h) {
      if (!h || !h.eid) return;
      const k = h.sid || h.mal;
      S.pendingH[k] = { ...h, at: h.at || Date.now() };
      schedule(8000);
    },
    flush() { clearTimeout(S.timer); S.timer = 0; return push(); },
    pull,
    push,
  };
})();
