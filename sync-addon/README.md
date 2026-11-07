# Общий прогресс просмотра: веб ↔ телевизор

Начали серию в браузере — на телевизоре она продолжится с того же места, и наоборот. Синхронизируются место в каждой серии и «Продолжить просмотр».

Прогресс хранится на сервере Yoru (`yoru.dohuyanevezuch.com`) в `data/sync/<id пользователя Shikimori>.json`. Ключ записи — id эпизода Anime365: он одинаковый у веба и у ТВ. Если записи расходятся, побеждает та, что сделана позже.

## Как входит каждое устройство

- **Веб** — обычной сессией Yoru, как и сейчас.
- **Телевизор** — заголовком `Authorization: Bearer <access_token Shikimori>`. Сервер один раз спрашивает у Shikimori, чей это токен (`/api/users/whoami`, ответ кэшируется на 10 минут), и пропускает через тот же список доступа, что и веб. Друзья с телевизором попадают каждый в свой прогресс.

## Что сделать на сервере

1. Положите `server/yoru-sync.cjs` рядом с `server.js`. Модуль без зависимостей, нужен Node 18+ (встроенный `fetch`).
2. В `server.js`:

```js
const createSync = require('./yoru-sync.cjs');   // для ESM: import createSync from './yoru-sync.cjs';

const sync = createSync({
  dataDir: path.join(__dirname, 'data'),
  appName: 'Yoru',                                 // = название приложения на Shikimori (User-Agent)
  getSessionUser: (req) => {                       // кто вошёл в веб; вернуть { id, nickname } или null
    const u = currentUser(req);                    // ← ваша функция сессии
    return u ? { id: u.shikiId, nickname: u.nickname } : null;
  },
  isAllowed: (user) => isAllowedNick(user.nickname), // ← ваш список доступа
});

// в обработчике запросов — до остальных маршрутов:
if (await sync.handle(req, res)) return;
```

Если сервер собран на Express: `app.use(async (req, res, next) => (await sync.handle(req, res)) || next());`

3. Если в Docker папка `data` вынесена в volume, `data/sync` окажется там же.

## Что сделать в вебе

1. Отдавайте `web/yoru-sync-web.js` как статический файл и подключите его после основного скрипта.
2. После входа вызовите `YoruSync.init({ userId })`. Он подтянет прогресс с сервера и дальше будет делать это при возвращении на вкладку.
3. Добавьте три вызова:
   - там, где сохраняется прогресс серии: `YoruSync.noteProgress(eid, t, d, { sid, mal })` (t и d в секундах);
   - там, где пишется история: `YoruSync.noteHistory({ sid, eid, mal, n, ep, title, poster, at })`;
   - перед тем как продолжить серию с сохранённого места: `await YoruSync.pull()`.

Если веб хранит прогресс и историю не в формате `yoru.<id>.progress` / `yoru.<id>.history`, передайте в `init` свои функции `readProgress`, `writeProgress`, `readHistory`, `writeHistory` и `historyFromServer`.

## API

| Метод | Путь | Тело / ответ |
|---|---|---|
| GET | `/api/sync?since=<ms>` | `{ now, progress: { [eid]: { t, d, at, sid?, mal? } }, history: [{ eid, sid, mal, n, ep, title, poster, at }] }` — только изменённое после `since` (время сервера) |
| POST | `/api/sync` | `{ progress: {…}, history: […] }` → `{ ok, now }` |

### Перевод и удаление (с версии 2)

- `progress[eid].tr` — id перевода Anime365, которым смотрели эту серию. Открывая серию, устройство сначала берёт этот перевод.
- `history[].trKind`, `trLang`, `trAuthor` — поля Anime365 `typeKind`, `typeLang`, `authorsSummary` перевода, которым смотрели тайтл последним. Для следующих серий перевод подбирается по ним (тот же автор и тип).
- `history[].del: true` — тайтл убрали из «Продолжить просмотр». Запись остаётся на сервере как метка: устройство, получив её, убирает тайтл у себя, если его запись старее метки. Новая обычная запись (смотрят снова) снимает метку.

Коды ответа: 401 — нет входа, 403 — пользователя нет в списке доступа, 404 — модуль не подключён (так телевизор и показывает в настройках).
