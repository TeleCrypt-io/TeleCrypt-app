# План: видеозвонки TeleCrypt с E2EE через Element Call (widget‑режим)

> Статус: **черновик / план работ**.
> Цель приоритет №1: рабочий сценарий **Desktop ↔ Desktop** с включённым
> per‑participant E2EE (perParticipantE2EE = true). Без костылей.
> Вторичная цель: альтернативные способы тестировать звонки на телефоне
> (без ElementX).

---

## 1. TL;DR

Текущий путь — встроить публичный `https://call.element.io` в headless
Chrome (через CDP) и «обмануть» его, отключив E2EE через URL/config — **не
работает и работать не будет**. Ниже подробно почему. Правильное решение —
запустить Element Call **в widget‑режиме** внутри iframe (или в том же
headless‑браузере, но с `widgetId/parentUrl` параметрами), а сам Matrix‑клиент
(аутентификацию, Olm, рассылку SFrame‑ключей) предоставлять из TeleCrypt
по протоколу Matrix Widget API через `postMessage`. Тогда:

* Element Call **не создаёт собственный Olm‑аккаунт** в IndexedDB браузера —
  он спрашивает host‑приложение «зашифруй/расшифруй вот это to‑device событие
  для меня». Нет split‑brain между vodozemac (trixnity) и Olm.js (EC).
* `perParticipantE2EE` в widget‑режиме **остаётся включённым** и работает,
  потому что SFrame‑ключи передаются через тот же Matrix‑клиент, который
  использует TeleCrypt, и Olm‑сессии у всех совпадают.
* Никаких хуков на `URL.createObjectURL`, `Worker`, `RTCRtpScriptTransform`,
  `URLSearchParams.prototype.get`, `<link rel=preload>`. Все «костыли»,
  которые сейчас лежат в [`CallLauncher.desktop.kt`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.desktop.kt)
  (инъекция `injectionScript` в строках 366–595) **выкидываются**.

---

## 2. Что сейчас не так (диагноз run17, коммит `9feecef`)

### 2.1. Соединение работает, видео — нет

* Оба пира входят в LiveKit‑комнату, появляются в `[MatrixLivekitMembers]`.
* Видео **из** TeleCrypt Desktop **доходит** до телефона (ElementX).
* Видео **с** телефона **не виден на десктопе** — приходит чёрный кадр.
* Worker EC пишет:
  * `set participant sender key @testuser:antidote.network:OJqiqUzuFt index 0/1/2`
  * `skipping decryption due to missing key at index 0`
  * `key for @dimarus05 ... at index 0 is being marked as invalid`

То есть **передача (TX) работает** — кадры шифруются и уходят. **Приём (RX)
ломается** — SFrame‑ключи отправителя не удаётся расшифровать на стороне
получателя.

### 2.2. Корневая причина — Olm split‑brain

Matrix Element Call раздаёт `m.call.encryption_keys` to‑device событиями,
зашифрованными Olm. Получатель должен иметь **ту же самую Olm‑сессию**
с устройством отправителя.

* TeleCrypt держит Olm‑аккаунт в **vodozemac** (trixnity, нативная либа).
  `device_id` лежит в Matrix store TeleCrypt'а.
* Element Call в standalone‑режиме при первом заходе вызывает `/register`
  или `/login` и кладёт **новый** Olm‑аккаунт в IndexedDB браузера
  (`matrix-js-sdk`/`olm.js`). У него **другой** curve25519 / ed25519, даже
  если мы подсовываем тот же `device_id` через `localStorage["matrix-auth-store"]`.
* В `/keys/query` сервер возвращает ключи vodozemac‑аккаунта. Удалённый клиент
  (телефон) шифрует SFrame‑ключ под **публичный ключ vodozemac**. Браузер EC
  на десктопе не может его расшифровать — у него Olm.js знает только свой
  собственный приватный ключ → `MissingKey` → чёрный кадр.

### 2.3. Почему «отключить E2EE через URL» не работает

* `perParticipantE2EE=false` в URL **молча игнорируется** EC v0.19.2 в standalone.
  В коде EC он читается через `useUrlParams().perParticipantE2EE`, но этот
  путь применим только в widget‑режиме. В standalone EC форсит E2EE из
  `config.json`/`useOptInAnalytics` логики.
* `config.json` грузится через `<link rel="preload">` в HTML, **не** через
  runtime `fetch()`. Наш fetch‑интерсептор его никогда не видит:
  > A preload for config.json is found, but is not used because the request
  > credentials mode does not match.
* SFrame‑шифрование живёт в **Web Worker**, созданном из `blob:` URL. Хук
  `RTCRtpScriptTransform` в main‑thread в Worker не пробрасывается.

Любая попытка «победить» E2EE patch'ами — это (1) большой и хрупкий патч;
(2) ломает совместимость с ElementX/Element Web (они шлют шифрованный
поток); (3) ломает приватность. Поэтому **отказываемся** от этого пути.

---

## 3. Правильное архитектурное решение — Widget Mode

Element Call **изначально проектировался** как Matrix Widget. В Element Web
и ElementX он живёт в `<iframe>`, host‑клиент общается с ним по
[MSC2774 Widget API](https://github.com/matrix-org/matrix-spec-proposals/blob/main/proposals/2774-widget-api-extensions.md)
через `window.postMessage`. В этом режиме:

* EC **не делает `/login`**, **не пишет в IndexedDB**, **не создаёт Olm**.
* На каждый chunk `m.call.encryption_keys` он просит host:
  «зашифруй это to‑device событие для устройств `[A, B, C]`».
* Host (TeleCrypt) делает это **своим** vodozemac → ключи матчатся → видео
  расшифровывается у получателя.
* `perParticipantE2EE` в widget‑URL **уважается** и работает.

### 3.1. Что нужно реализовать

Список модулей/слоёв, в порядке зависимостей:

1. **HTTP‑сервер на localhost** (порт `127.0.0.1:<random>`), отдающий
   маленькую HTML‑страницу `widget-host.html`. Эта страница:
   * Загружает `<iframe src="https://call.element.io/room/#/...?widgetId=...&parentUrl=...">`.
   * Слушает `window.message` от iframe (Widget API).
   * Пробрасывает запросы по WebSocket / fetch на JVM‑сторону TeleCrypt.
   * Отдаёт ответы обратно в iframe через `iframe.contentWindow.postMessage`.

2. **JVM‑слой моста (`WidgetBridgeServer`)** на `Ktor` (он уже есть в трикснити):
   * Эндпоинты:
     * `GET /widget-host.html` — статика.
     * `WS /widget-bus` — двунаправленный канал «браузер ↔ Kotlin».
   * Ответственность:
     * Принимает Widget API запросы (`MSC2876_request_capabilities`,
       `send_to_device`, `send_event`, `read_events`, `update_turn_servers`,
       `m.always_on_screen`).
     * Транслирует их в вызовы [`MatrixClient`](../../trixnity/trixnity-client/src/commonMain/kotlin/de/connect2x/trixnity/client/MatrixClient.kt:1)
       (sendToDevice, sendStateEvent и т.д.).
     * Возвращает события sync (`m.room.member`, `org.matrix.msc3401.call.member`
       state, `m.call.*` to‑device) в виде Widget API events.

3. **Widget URL builder** — заменяет
   [`buildElementCallUrl()`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt:21).
   Параметры (см. `element-call/src/widget.ts`):
   * `widgetId` — UUID, генерируется на каждый запуск.
   * `parentUrl` — `http://127.0.0.1:<port>/widget-host.html`.
   * `clientId` — `io.element.call.<random>`.
   * `userId`, `deviceId` — из MatrixClient.
   * `roomId`, `baseUrl` (homeserver), `lang`.
   * `perParticipantE2EE=true` — уважается.
   * `intent=start_call` / `join_existing`.

4. **`CallLauncher.desktop.kt`** — упрощается:
   * Запускает `WidgetBridgeServer.start()`, получает порт.
   * Открывает headless Chrome через CDP не на `https://call.element.io/...`,
     а на `http://127.0.0.1:<port>/widget-host.html?roomId=...`.
   * Удаляются: `localStorage["matrix-auth-store"]` инъекция,
     `URLSearchParams` override, `RTCRtpScriptTransform` stub,
     fetch‑override `/register`/`/login`/`config.json`.
   * Остаётся только: подсветка браузерного окна, kill старых процессов,
     remote‑debugging port для логов.

5. **Сериализация Widget API сообщений.** Каждое сообщение Widget API имеет
   формат:
   ```json
   {"api":"fromWidget"|"toWidget","widgetId":"...","requestId":"...",
    "action":"send_to_device"|"send_event"|...,"data":{...},"response":{...}}
   ```
   Нужен Kotlin parser/serializer (kotlinx.serialization) для всех action'ов,
   которые EC реально шлёт. Реальный список — из исходников
   [`element-call/src/widget.ts`](https://github.com/element-hq/element-call/blob/livekit/src/widget.ts)
   и `matrix-widget-api/src/models/WidgetApi.ts`.

6. **Capabilities**, которые требует EC:
   * `org.matrix.msc2762.send.event:org.matrix.msc3401.call.member`
   * `org.matrix.msc2762.receive.event:org.matrix.msc3401.call`
   * `org.matrix.msc3819.send.to_device:m.call.encryption_keys`
   * `org.matrix.msc3819.receive.to_device:m.call.encryption_keys`
   * `m.always_on_screen`
   * `org.matrix.msc4157.send.delayed_event`
   * `org.matrix.msc2762.timeline:!<roomId>`

   Capabilities выдаются **молча** (`ack`), без UI prompt'а, потому что
   EC — доверенный «системный» виджет.

### 3.2. Поток данных в widget‑режиме (упрощённо)

```
+-------------------+   ws    +------------------+   sync/sendToDevice    +----------+
|  Chrome (CDP)     | <-----> | Kotlin Widget    | <--------------------> | Matrix   |
|  iframe EC        |         | Bridge (Ktor)    |                        | server   |
|  postMessage API  |         | uses MatrixClient|                        |          |
+-------------------+         +------------------+                        +----------+
        ^                              ^
        |                              |
        +-- LiveKit WebRTC media ------+--- (TURN/SFU как и сейчас)
```

* Видео‑/аудио‑потоки идут напрямую из Chrome в LiveKit SFU — мост в этом
  не участвует.
* SFrame‑ключи (`m.call.encryption_keys`) идут через мост → trixnity
  → Matrix `/sendToDevice` → удалённый пир → его мост → его виджет.
* Olm шифрование делает trixnity (vodozemac), не Olm.js. Получатель тоже
  расшифровывает через trixnity. **Split‑brain'а нет.**

---

## 4. Альтернативные способы тестирования телефона (без ElementX)

Нам нужен второй «полноценный» Matrix‑клиент с поддержкой Element Call,
но не ElementX. Варианты:

### 4.1. (Рекомендую) Вторая инстанция TeleCrypt на отдельном устройстве

* TeleCrypt — Kotlin Multiplatform, уже есть `androidMain`. Собрать APK
  (`./gradlew :TeleCrypt:assembleDebug`) и залить на телефон.
* Преимущество: **тот же стек** (vodozemac + widget‑мост) — диагностируем
  ровно ту функцию, которую делаем.
* Недостаток: пока widget‑мост не дописан до Android — звонок через мобильный
  TeleCrypt тоже не поедет. Поэтому промежуточный шаг — **2 десктопа**.

### 4.2. (Самый быстрый промежуточный шаг) Два десктопа на одном Mac

Запустить второй процесс TeleCrypt с разной `TRIXNITY_MESSENGER_ROOT_PATH`:

```bash
# терминал A
TRIXNITY_MESSENGER_ROOT_PATH=$HOME/.tcA \
TRIXNITY_MESSENGER_MULTI_INSTANCE=1 \
./gradlew :TeleCrypt:run

# терминал B
TRIXNITY_MESSENGER_ROOT_PATH=$HOME/.tcB \
TRIXNITY_MESSENGER_MULTI_INSTANCE=1 \
./gradlew :TeleCrypt:run
```

Два логина — звонок A↔B. Это и есть наш приоритет №1.

### 4.3. Element Web в мобильном браузере

* `app.element.io` — это та же кодовая база, что и ElementX по сути,
  но widget‑host. С неё звонок поедет в EC widget‑режиме.
* На Android Chrome требует HTTPS для доступа к камере/микрофону;
  https://app.element.io это удовлетворяет.
* Не идеально — но позволяет хоть как‑то воспроизвести «desktop ↔ phone»
  без ElementX.

### 4.4. Запустить ещё один EC standalone в Chrome на телефоне

Открыть `https://call.element.io/<roomAlias>` в мобильном Safari/Chrome,
залогиниться через тот же Matrix homeserver. Это будет EC standalone —
с теми же проблемами Olm split‑brain, что и сейчас. **Не годится** для
проверки E2EE — но годится для проверки чисто LiveKit‑транспорта.

---

## 5. План работ (по шагам)

### Этап 0. Откат «костылей» (≈1 коммит, ~30 минут)

* [ ] [`ElementCallUrl.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt:35):
      `perParticipantE2EE` default = **`true`**.
* [ ] [`CallLauncher.desktop.kt`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.desktop.kt):
      убрать из `injectionScript` блок `RTCRtpScriptTransform`/Worker
      (строки 503–595, коммит `9feecef`), убрать
      `URLSearchParams.prototype.get` override, убрать перехват
      `config.json`. Оставить только инъекцию `matrix-auth-store` (как
      минимальный fallback на время разработки widget‑моста).

### Этап 1. Минимальный Widget Bridge Server на JVM (≈2–3 файла, ~1 день)

* [ ] Новый модуль `desktopMain/.../call/widgetBridge/WidgetBridgeServer.kt`:
      Ktor Netty engine, `127.0.0.1:0`, эндпоинты `/widget-host.html`
      и `WS /widget-bus`.
* [ ] Новый файл `desktopMain/.../call/widgetBridge/widget-host.html`
      (resource): минимальный HTML с iframe + JS bridge `postMessage <-> WS`.
* [ ] Новый класс `commonMain/.../call/widgetBridge/WidgetApiHandler.kt`:
      обработка действий `capabilities`, `send_to_device`, `send_event`,
      `read_events`, `update_turn_servers`, `m.always_on_screen`.
      Внутри использует `MatrixClient` (api.eventApi.sendToDevice и т.д.).

### Этап 2. URL builder и интеграция (≈0.5 дня)

* [ ] Новый `buildElementCallWidgetUrl(...)` в [`ElementCallUrl.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt):
      возвращает widget URL с `widgetId, parentUrl, clientId, userId,
      deviceId, baseUrl, lang, perParticipantE2EE=true, intent=...`.
* [ ] [`CallCoordinatorImpl.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callRtc/CallCoordinatorImpl.kt:31)
      вызывает `WidgetBridgeServer.start()`, передаёт результирующий URL
      в `CallLauncher`.
* [ ] [`CallLauncher.desktop.kt`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.desktop.kt):
      открывает Chrome на `widget-host.html`, без всяких injection script'ов.

### Этап 3. Прокачка Widget API до полной совместимости с EC (≈1–2 дня)

* [x] Реализовать все action'ы, которые EC v0.19.2 реально шлёт. Брать
      список из релизных тегов
      [`element-hq/element-call`](https://github.com/element-hq/element-call/blob/livekit-v0.19.2/src/widget.ts).
* [x] Auto‑accept capabilities (мы trusted host) — host‑initiated `capabilities`
      запрос плюс ack на `fromWidget` `capabilities`.
* [x] Реальный `send_to_device` через
      [`UserApiClient.sendToDeviceUnsafe()`](../../trixnity/trixnity-clientserverapi/trixnity-clientserverapi-client/src/commonMain/kotlin/de/connect2x/trixnity/clientserverapi/client/UserApiClient.kt:75).
* [x] Реальный `send_event` для state events через
      [`RoomApiClient.sendStateEvent()`](../../trixnity/trixnity-clientserverapi/trixnity-clientserverapi-client/src/commonMain/kotlin/de/connect2x/trixnity/clientserverapi/client/RoomApiClient.kt:199).
* [x] Реальный `org.matrix.msc2876.read_events` через
      [`RoomApiClient.getState()`](../../trixnity/trixnity-clientserverapi/trixnity-clientserverapi-client/src/commonMain/kotlin/de/connect2x/trixnity/clientserverapi/client/RoomApiClient.kt) — фильтрация по `type`/`state_key`,
      сборка полного envelope.
* [x] Запушить sync‑события в iframe (state events комнаты, to‑device):
      [`BridgeForwardingRegistry`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/BridgeForwardingRegistry.kt:1)
      связывает `(userId, roomId)` и активный мост; в
      [`MatrixRtcSyncEventHandler.forwardToBridgeIfNeeded()`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callRtc/MatrixRtcSyncEventHandler.kt:1)
      `m.call.member`/`org.matrix.msc3401.call.member` уезжают в
      [`BridgeSession.forwardSyncEvent()`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetBridgeManager.kt:42),
      `m.call.encryption_keys` (to-device) — в
      [`BridgeSession.forwardToDeviceEvent()`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetBridgeManager.kt:42).
* [ ] Ручной прогон: EC iframe загружается без «Произошла ошибка»,
      `[WidgetApi] read_events ... -> N events`, `[BridgeRegistry] register/unregister`.

### Этап 4. Тестирование Desktop ↔ Desktop (≈0.5 дня)

* [ ] Запустить два экземпляра TeleCrypt по схеме из §4.2.
* [ ] Видеозвонок: A → B, проверить что **видео идёт в обе стороны** при
      `perParticipantE2EE=true`.
* [ ] Проверить логи: не должно быть `MissingKey`, `skipping decryption`.

### Этап 5. (опционально) Android и phone↔desktop

* [ ] Сделать `WidgetBridgeServer` доступным в `androidMain` (Ktor работает).
* [ ] [`CallLauncher.android.kt`](../src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.android.kt:74):
      аналогично — открыть widget‑host в WebView, не в EC standalone.

---

## 6. Что я делаю прямо сейчас (после этой доки)

1. Откатываю «костыли» E2EE (этап 0). Это маленькие, но жирные diff'ы в
   [`ElementCallUrl.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/ElementCallUrl.kt)
   и
   [`CallLauncher.desktop.kt`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.desktop.kt).
2. Поднимаю минимальный `WidgetBridgeServer` (этап 1) на Ktor.
3. Делаю widget URL builder и переключаю CDP‑запуск на него (этап 2).
4. Прокручиваю Widget API до того состояния, при котором EC «оживает»
   внутри iframe (этап 3).
5. Прогоняю A↔B сценарий (этап 4).

Все эти шаги — без хаков, в чистом коде, в правильных слоях.
