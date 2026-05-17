# TeleCrypt — Mobile Widget Mode Plan (Stage 5)

> **Статус:** Планирование. Desktop widget mode работает (Mac↔Mac ✅, Mac↔Windows в процессе).
> Этот документ описывает, что нужно сделать для Android, и как тестировать без опыта мобильной разработки.

---

## 1. Зачем это нужно

Сейчас на Android [`CallLauncher.android.kt`](../src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.android.kt:74) открывает Element Call в WebView в **standalone-режиме** — EC сам создаёт Olm-аккаунт в IndexedDB WebView. Это **Olm split-brain**: у EC свои ключи, у TeleCrypt свои → `BAD_MESSAGE_MAC` → видео не расшифровывается.

В **widget-режиме** EC грузится в WebView, но не имеет доступа к Matrix API напрямую. Вместо этого он общается с TeleCrypt через `postMessage` ↔ WebSocket-мост (тот же [`WidgetApiHandler.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetApiHandler.kt:36), что и на desktop). Olm-контекст принадлежит TeleCrypt → E2EE работает.

---

## 2. Ключевое отличие Android от Desktop

| | Desktop | Android |
|---|---|---|
| Где грузится EC | Chrome (внешний процесс) | `WebView` внутри `Activity` |
| Как EC общается с мостом | WebSocket к `ws://127.0.0.1:<port>` | **Тот же WebSocket** к `ws://127.0.0.1:<port>` |
| Кто поднимает HTTP-сервер | [`WidgetBridgeServer.kt`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetBridgeServer.kt:30) | **Тот же** `WidgetBridgeServer.kt` (JVM-код, работает на Android) |
| Кто открывает host-страницу | `openCallWindow()` → Chrome | `WebView.loadUrl(hostUrl)` |
| `WidgetBridgeManager` | `DesktopWidgetBridgeManager` | Нужен `AndroidWidgetBridgeManager` (почти копия) |

**Хорошая новость:** `WidgetBridgeServer.kt` написан на чистом JVM (java.net.ServerSocket) — он уже работает на Android без изменений. `WidgetApiHandler.kt` — commonMain, тоже без изменений. Нужно только:
1. Создать `AndroidWidgetBridgeManager` (аналог `DesktopWidgetBridgeManager`, но без `System.currentTimeMillis().toString(36)` — это тоже работает на Android).
2. Переписать `ElementCallActivity` — вместо standalone URL грузить `hostUrl` из bridge.
3. Поменять `callBackendModule.android.kt` — заменить `NoopWidgetBridgeManager` на `AndroidWidgetBridgeManager`.

---

## 3. Архитектура Android widget mode

```
TeleCrypt App (Android)
│
├── CallCoordinatorImpl (commonMain)
│   └── startWidgetBridge() → AndroidWidgetBridgeManager.start()
│       └── WidgetBridgeServer (JVM, порт ~40000-50000)
│           ├── GET /  → widget-host.html (с подставленными URL)
│           └── WS /ws → WidgetApiHandler (commonMain)
│
└── ElementCallActivity
    └── WebView
        └── widget-host.html (загружена с http://127.0.0.1:<port>/)
            └── <iframe src="https://call.element.io/...?widgetId=...">
                └── EC ←→ postMessage ←→ widget-host.html ←→ WebSocket ←→ WidgetApiHandler
```

**Поток данных:**
1. Пользователь нажимает "Позвонить" → `CallCoordinatorImpl.startCall()`
2. `AndroidWidgetBridgeManager.start()` поднимает `WidgetBridgeServer` на `127.0.0.1:<random_port>`
3. `CallCoordinatorImpl` получает `hostUrl = "http://127.0.0.1:<port>/"`
4. `ElementCallLauncherImpl.joinByWidgetUrl(hostUrl)` запускает `ElementCallActivity` с этим URL
5. `ElementCallActivity` грузит `hostUrl` в WebView
6. WebView загружает `widget-host.html`, который открывает WS к `127.0.0.1:<port>/ws` и грузит EC в iframe
7. EC ↔ postMessage ↔ widget-host.html ↔ WebSocket ↔ `WidgetApiHandler` ↔ trixnity

---

## 4. Что нужно написать (конкретные изменения)

### 4.1. Новый файл: `AndroidWidgetBridgeManager.kt`

**Путь:** `TeleCrypt/src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetBridgeManager.android.kt`

Это почти точная копия [`DesktopWidgetBridgeManager`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetBridgeManager.desktop.kt:22). Отличия:
- Класс называется `AndroidWidgetBridgeManager`
- Импорты те же (JVM-классы работают на Android)
- Весь код `doSendToDevice`, `doSendStateEvent`, `doSendMessageEvent`, `doReadStateEvents`, `doGetOpenIdToken`, `retryOnTransientFailure`, `isTransient`, `stateEventToCanonicalJson` — **идентичен**

> **Почему не вынести в commonMain?** `WidgetBridgeServer` использует `java.net.ServerSocket` — это JVM/Android, не KMP. Можно вынести логику коллбеков в `jvmMain` (shared между desktop и android), но это рефакторинг. Для первой итерации — просто скопировать.

### 4.2. Изменить: `callBackendModule.android.kt`

**Путь:** [`TeleCrypt/src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackendModule.android.kt`](../src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackendModule.android.kt:13)

```kotlin
// БЫЛО:
single<WidgetBridgeManager> {
    NoopWidgetBridgeManager()
}

// СТАЛО:
single<WidgetBridgeManager> {
    AndroidWidgetBridgeManager()
}
```

### 4.3. Изменить: `ElementCallActivity` и `ElementCallLauncherImpl`

**Путь:** [`TeleCrypt/src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.android.kt`](../src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.android.kt:16)

Нужно:
1. `ElementCallLauncherImpl.joinByWidgetUrl(hostUrl)` — переопределить, чтобы запускать `ElementCallActivity` с `hostUrl` (сейчас `joinByWidgetUrl` наследует дефолт `joinByUrl`, что тоже сработает, но нужно убедиться что Activity правильно настроена).
2. `ElementCallActivity.configureWebView()` — добавить разрешения для `file://` и `http://localhost` (нужно для загрузки host-страницы с локального сервера):

```kotlin
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    mediaPlaybackRequiresUserGesture = false
    databaseEnabled = true
    // Нужно для widget-mode: WebView должен разрешить WS к 127.0.0.1
    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW  // http iframe в http host
    allowContentAccess = true
}
```

3. Добавить `WebViewAssetLoader` или просто убедиться, что `http://127.0.0.1:<port>/` загружается без ограничений (Android WebView по умолчанию разрешает `http://127.0.0.1`).

### 4.4. Разрешения в AndroidManifest

Проверить что в `AndroidManifest.xml` есть:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

И `ElementCallActivity` объявлена:
```xml
<activity android:name=".telecryptModules.call.callBackend.ElementCallActivity"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:screenOrientation="portrait" />
```

---

## 5. Потенциальные проблемы

### 5.1. WebView и `http://127.0.0.1` (CORS / mixed content)
EC грузится с `https://call.element.io` в iframe, а host-страница — с `http://127.0.0.1`. Это **mixed content** (https iframe в http host). Браузеры блокируют это, но Android WebView с `MIXED_CONTENT_ALWAYS_ALLOW` — нет.

Альтернатива: поднять HTTPS на localhost (самоподписанный сертификат) — сложнее, не нужно для первой итерации.

### 5.2. WebSocket из iframe к `ws://127.0.0.1`
EC внутри iframe пытается открыть WebSocket к `ws://127.0.0.1:<port>/ws`. Это работает в Chrome (desktop), но в Android WebView может быть заблокировано политикой `cleartext traffic`. Нужно добавить в `AndroidManifest.xml`:
```xml
<application android:usesCleartextTraffic="true" ...>
```
Или использовать `network_security_config.xml`:
```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">localhost</domain>
    </domain-config>
</network-security-config>
```

### 5.3. Камера/микрофон в WebView
`WebChromeClient.onPermissionRequest` уже реализован в `ElementCallActivity` — он автоматически разрешает все запросы. Это правильно для widget-mode.

### 5.4. Жизненный цикл Activity vs Bridge
Когда пользователь сворачивает приложение или поворачивает экран, `ElementCallActivity` может быть пересоздана. `WidgetBridgeServer` при этом продолжает работать (он в корутине TeleCrypt, не в Activity). Нужно убедиться, что при `onDestroy` Activity мы не закрываем bridge — это делает `CallCoordinatorImpl.leaveCall()`.

---

## 6. Как тестировать Android (для новичка в мобильной разработке)

### 6.1. Что такое Android Emulator

Android Emulator — это виртуальный телефон, который запускается прямо на Mac. Не нужен физический телефон. Устанавливается через **Android Studio**.

### 6.2. Установка Android Studio

1. Скачать с [developer.android.com/studio](https://developer.android.com/studio) → установить.
2. При первом запуске: **SDK Manager** → установить Android SDK (API 34, Android 14).
3. **AVD Manager** (Android Virtual Device) → **Create Virtual Device** → выбрать "Pixel 7" → выбрать "API 34" → Finish.

### 6.3. Сборка TeleCrypt для Android

В проекте уже настроен Android target в [`build.gradle.kts`](../build.gradle.kts:114). Для сборки:

```bash
cd TeleCrypt
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
./gradlew assembleDebug
```

APK появится в `TeleCrypt/build/outputs/apk/debug/TeleCrypt-debug.apk`.

### 6.4. Установка на эмулятор

```bash
# Запустить эмулятор (если не запущен через Android Studio)
~/Library/Android/sdk/emulator/emulator -avd Pixel_7_API_34 &

# Установить APK
~/Library/Android/sdk/platform-tools/adb install TeleCrypt/build/outputs/apk/debug/TeleCrypt-debug.apk
```

Или проще: открыть проект в Android Studio → нажать зелёную кнопку ▶ (Run) → выбрать эмулятор.

### 6.5. Просмотр логов (Logcat)

Логи Android — аналог `println` на desktop. Смотреть через:

```bash
# Все логи TeleCrypt
~/Library/Android/sdk/platform-tools/adb logcat -s "TeleCrypt" "*:E"

# Или фильтровать по тегу WidgetBridge
~/Library/Android/sdk/platform-tools/adb logcat | grep -E "WidgetBridge|WidgetApi|Call"
```

В Android Studio: вкладка **Logcat** внизу → фильтр по `package:de.connect2x.tammy`.

### 6.6. Сценарий теста Android↔Desktop

1. **Mac** (`@testuser`): запустить TeleCrypt desktop, войти в комнату.
2. **Эмулятор** (`@dimarus05`): запустить TeleCrypt Android, войти в ту же комнату.
3. **Mac**: нажать **Start Call**.
4. **Эмулятор**: должно появиться уведомление о входящем звонке → нажать **Join**.
5. Проверить:
   - Оба видят свой preview ✓
   - Mac видит видео с эмулятора ✓ (эмулятор использует виртуальную камеру)
   - Эмулятор видит видео с Mac ✓
   - В логах нет `BAD_MESSAGE_MAC` ✓

### 6.7. Виртуальная камера в эмуляторе

Эмулятор Android имеет встроенную виртуальную камеру (показывает движущийся паттерн или изображение). Для теста этого достаточно — главное что WebRTC-поток идёт.

Можно также использовать **физический телефон** (Android 8+):
1. Включить **Developer Options** → **USB Debugging**.
2. Подключить по USB.
3. `adb devices` — должен появиться в списке.
4. `adb install TeleCrypt-debug.apk` — установить.

---

## 7. Порядок реализации (этапы)

### Этап 5.1 — AndroidWidgetBridgeManager (~2 часа)
- [ ] Создать `WidgetBridgeManager.android.kt` (копия desktop-версии)
- [ ] Обновить `callBackendModule.android.kt`
- [ ] Compile-check: `./gradlew compileDebugKotlin`

### Этап 5.2 — ElementCallActivity widget mode (~1 час)
- [ ] Переопределить `joinByWidgetUrl` в `ElementCallLauncherImpl`
- [ ] Добавить `MIXED_CONTENT_ALWAYS_ALLOW` в WebView settings
- [ ] Добавить `network_security_config.xml` для cleartext к 127.0.0.1
- [ ] Compile-check

### Этап 5.3 — Первый запуск на эмуляторе (~1 час)
- [ ] Установить Android Studio + эмулятор
- [ ] `assembleDebug` + установить APK
- [ ] Проверить что приложение запускается, логинится
- [ ] Проверить что кнопка звонка появляется в комнате

### Этап 5.4 — Тест Android↔Desktop (~1 час)
- [ ] Запустить звонок с Desktop → Android получает уведомление
- [ ] Android присоединяется → проверить логи `[WidgetBridge]`
- [ ] Проверить видео/аудио в обе стороны
- [ ] Убедиться что нет `BAD_MESSAGE_MAC`

---

## 8. Ключевые файлы для изменения

| Файл | Что менять |
|------|-----------|
| [`CallLauncher.android.kt`](../src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackend/CallLauncher.android.kt:16) | `ElementCallActivity` — WebView settings; `joinByWidgetUrl` |
| [`callBackendModule.android.kt`](../src/androidMain/kotlin/de/connect2x/tammy/telecryptModules/call/callBackendModule.android.kt:13) | `NoopWidgetBridgeManager` → `AndroidWidgetBridgeManager` |
| `WidgetBridgeManager.android.kt` | **Новый файл** — копия desktop-версии |
| `AndroidManifest.xml` | `usesCleartextTraffic` или `network_security_config` |
| `res/xml/network_security_config.xml` | **Новый файл** — разрешить http к 127.0.0.1 |

---

## 9. Что НЕ нужно менять

- [`WidgetBridgeServer.kt`](../src/desktopMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetBridgeServer.kt:30) — JVM-код, работает на Android без изменений
- [`WidgetApiHandler.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/WidgetApiHandler.kt:36) — commonMain, без изменений
- [`widget-host.html`](../src/desktopMain/resources/widget-host.html:1) — тот же HTML, без изменений
- [`CallCoordinatorImpl.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callRtc/CallCoordinatorImpl.kt:34) — commonMain, без изменений
- [`BridgeForwardingRegistry.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/widgetBridge/BridgeForwardingRegistry.kt:20) — commonMain, без изменений
- [`MatrixRtcSyncEventHandler.kt`](../src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/callRtc/MatrixRtcSyncEventHandler.kt:32) — commonMain, без изменений

---

## 10. Почему это проще, чем кажется

На desktop мы потратили много времени на:
- Написание `WidgetBridgeServer` (HTTP+WS с нуля на java.net)
- Написание `WidgetApiHandler` (весь Matrix Widget API)
- Отладку MSC4140, MSC2876, Olm-шифрование

**Всё это уже готово и работает.** Для Android нужно только:
1. Скопировать `DesktopWidgetBridgeManager` → `AndroidWidgetBridgeManager` (~380 строк, без изменений)
2. Поменять 3 строки в `callBackendModule.android.kt`
3. Добавить 2 строки в WebView settings
4. Добавить `network_security_config.xml` (5 строк XML)

Итого: ~400 строк кода, большинство из которых — копипаст уже работающего кода.
