# TeleCrypt Overlay

Этот репозиторий теперь хранит только наш слой-оверлей и брендинг. Исходники Tammy/TRIXNITY не лежат здесь: они подтягиваются при сборке.

## Как собрать
1. Убедитесь, что `overlay/config.json` указывает на нужные SHA апстрима (сейчас `tammy main`).
2. Запустите:
   ```bash
   ./overlay/bootstrap.sh
   ./overlay/build.sh        # по умолчанию desktop/web/Android bundle
   # или свои задачи:
   ./overlay/build.sh bundleRelease assembleRelease
   ```
   Скрипт кладёт исходники в `overlay/workspace/layers/telecrypt-app`, применяет `branding/branding.json`, затем гонит Gradle.

## Файлы
- `overlay/` — bootstrap/build скрипты.
- `branding/` — наш brandify-конфиг и иконки.
- `tools/brandify.sh` / `brandify.kts` — брендинг.

## CI
В `.github/workflows/ci.yml` нужно запускать `overlay/bootstrap.sh`, затем `overlay/build.sh <tasks>` из workspace `overlay/workspace/layers/telecrypt-app`.
