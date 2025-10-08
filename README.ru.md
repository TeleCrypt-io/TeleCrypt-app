<p align="right">
  <a href="README.md">English</a> | Русский
</p>

# TeleCrypt Messenger

TeleCrypt — это брендированная версия Matrix‑клиента Tammy (Kotlin Multiplatform + Compose).  
Здесь собран наш вариант приложения, автоматизация ребрендинга и GitLab CI, который собирает
Android, Desktop и Web артефакты для каждого Merge Request и для ветки `main`.

## Ключевые директории
- `branding/branding.json` — конфигурация бренда (имя приложения, Android/iOS идентификаторы, набор иконок).
- `tools/brandify.sh` / `tools/brandify.kts` — идемпотентные скрипты ребрендинга, которые используют локально и в CI.
- `.gitlab-ci.yml` — описание пайплайна (Linux раннеры, ручные этапы для Windows/macOS/iOS).
- `docs/` — внутренние заметки, бэклог, cookbook по сборке/публикации.

## Требования
- JDK 21 (Gradle сам подтягивает нужный toolchain).
- Android SDK для локальной сборки Android. Укажите `ANDROID_HOME` или пропишите `sdk.dir` в `local.properties`.
- Node/Yarn устанавливаются Kotlin JS плагином автоматически.
- Ruby + Bundler нужны только для запуска Fastlane (`bundle install`).

## Ребрендинг
1. Отредактируйте `branding/branding.json`:
   - `appName` — отображаемое имя.
   - `androidAppId` — `applicationId` для Android (для dev-сборок автоматически добавляется `.dev`).
   - `iosBundleId` — необязательный параметр (по умолчанию равен Android ID).
   - `iconDir` — папка с иконками Android/iOS/Desktop (структура в `docs/branding.md`).
2. Запустите скрипт:
   ```bash
   tools/brandify.sh branding/branding.json
   # или
   tools/brandify.kts branding/branding.json
   ```
3. Скрипт:
   - Обновит `build.gradle.kts`, `settings.gradle.kts` (slug проекта), `fastlane/Appfile`,
     iOS-конфиги, Flatpak шаблоны и `google-services.json`.
   - Не трогает Kotlin-пакеты (`de.connect2x.tammy`), чтобы не ломать архитектуру.
   - Подменяет иконки Android/iOS/Desktop из `branding/icons`.

Скрипт нужно запускать перед сборкой (CI делает это автоматически), особенно после синхронизации с Tammy.

## Сборка локально
Используйте локальный кэш Gradle (`GRADLE_USER_HOME=$PWD/.gradle`), чтобы не засорять систему.

| Цель | Команда | Артефакты |
| --- | --- | --- |
| Android (release AAB/APK) | `GRADLE_USER_HOME=$PWD/.gradle ./gradlew bundleRelease assembleRelease` | `build/outputs/bundle/release/<archiveBase>-release.aab`, `build/outputs/apk/release/<archiveBase>-release.apk` |
| Desktop (текущая ОС) | `GRADLE_USER_HOME=$PWD/.gradle ./gradlew createReleaseDistributable packageReleasePlatformZip` | `build/compose/binaries/main-release/**` |
| Desktop extras | `./gradlew packageReleaseDmg packageReleaseMsix packageReleaseFlatpakBundle packageReleaseFlatpakSources packageReleaseWebZip` | DMG/MSIX/Flatpak/Web архивы в `build/compose/binaries/main-release/**` |
| Web dev | `./gradlew webBrowserDevelopmentRun` | Локальный dev-сервер |
| Web distributable | `./gradlew uploadWebZipDistributable` | Готовый zip (используется и в CI) |
| iOS archive (ручной) | `cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme "Tammy for iOS" -configuration Release -archivePath build/TeleCrypt.xcarchive archive` | `iosApp/build/TeleCrypt.xcarchive` |

> Для Android задач понадобится установленный SDK. В Docker-образах GitLab он уже включён.

## CI/CD
- **Merge Request и `main`** — Linux раннеры:
  - `build:android` → `uploadAndroidDistributable` (AAB/APK + загрузка в GitLab Package Registry).
  - `build:linux-x64`, `build:web`, `build:prepare-website`, `build:website` — сборка десктопных и веб артефактов.
- **Ручные джобы** (нужны отдельные раннеры и секреты):
  - `build:windows-x64`, `build:macos-x64`, `build:macos-arm64`, все `release:*`.
  - iOS джоба закомментирована — потребуется macOS runner с 8+ ГБ ОЗУ.
- **Секреты** добавляются в GitLab → Settings → CI/CD → Variables (masked + protected). Не коммитим их в репо.

### Переменные CI
| Переменная | Назначение | Формат |
| --- | --- | --- |
| `ANDROID_RELEASE_STORE_FILE_BASE64` | Подписной Android keystore | Base64 `.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | Пароль к keystore | Текст |
| `ANDROID_RELEASE_KEY_ALIAS` | Alias внутри keystore | Текст |
| `ANDROID_RELEASE_KEY_PASSWORD` | Пароль ключа | Текст |
| `ANDROID_SERVICE_ACCOUNT_JSON_BASE64` | Сервисный аккаунт Google Play | Base64 JSON |
| `APPLE_KEYCHAIN_FILE_BASE64` | Временный кейчейн для macOS джоб | Base64 keychain |
| `APPLE_KEYCHAIN_PASSWORD` | Пароль к кейчейну | Текст |
| `APPLE_TEAM_ID` | Apple Developer Team ID | Текст |
| `APPLE_ID` | Apple ID для notarisation/TestFlight | Текст |
| `APPLE_NOTARIZATION_PASSWORD` | App-specific password | Текст |
| `WINDOWS_CODE_SIGNING_THUMBPRINT` | SHA-1 отпечаток сертификата | HEX |
| `WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER` | URL таймстамп-сервера | URL |
| `SSH_PASSWORD_APP` / `SSH_PASSWORD_WEBSITE` | Пароли для SFTP деплоя | Текст |

Заполните их перед включением релизных этапов. Убедитесь, что аккаунты Google Play и App Store Connect выданы нужным пользователям.

### Раннеры
- **Linux** — GitLab SaaS shared runners (уже используются).
- **macOS** — нужен для DMG + notarisation и для iOS/TestFlight. Требуются Xcode 16+, Ruby/Bundler, JDK 21.
- **Windows** — нужен для MSIX и подписи (Windows 11, Windows SDK, `signtool.exe`).

Документируйте настройку раннеров в `docs/`, когда будете их поднимать (пакеты, переменные, кеши).

## Синхронизация с Tammy
`tools/upstream_sync.sh` подтягивает `main` из Tammy, делает fast-forward merge и заново запускает ребрендинг.

```bash
bash tools/upstream_sync.sh            # синхронизация main, автоматический push
UPSTREAM_REMOTE=upstream-dev \
UPSTREAM_URL=git@github.com:connect2x/tammy.git \
PUSH_UPDATES=false \
  bash tools/upstream_sync.sh develop  # кастомный remote/branch без push
```

Требования:
- Чистое рабочее дерево (скрипт остановится, если есть незакомиченные изменения).
- Remote `upstream` добавится автоматически, если его ещё нет.
- После мерджа автоматически запускается `tools/brandify.sh`, если найден `branding/branding.json`.

## Отладка
- **Ошибки BuildConfig** — убедитесь, что запускали brandify; файлы в `build/generatedSrc/**` должны содержать пакет `de.connect2x.tammy`, но возвращать брендированные значения.
- **`kotlinNpmInstall` / “Name contains illegal characters”** — снова запустите brandify, чтобы `settings.gradle.kts` получил корректный slug без пробелов.
- **Android жалуется на SDK** — настройте `ANDROID_HOME` или пропишите `sdk.dir` в `local.properties`.
- **Fastlane пути** — при смене имени приложения brandify обновит `fastlane/Appfile`; не забудьте перезапустить скрипт после правок `appName`.

Подробный порядок релизов смотрите в `docs/HowToBuildAndPublish.md`.
