# Overlay bootstrap

Этот каталог — наш оверлей (Layer 4). Он тянет исходники Tammy по зафиксированному SHA, применяет брендинг и готовит workspace для сборки.

## Files
- `config.example.json` – пример конфига; скопируйте в `overlay/config.json` и укажите нужные URL/ветку.
- `bootstrap.sh` – клонирует Tammy, checkout указанный ref и раскладывает в `overlay/workspace/layers/telecrypt-app`.
- `build.sh` – применяет брендинг (`branding/branding.json` + `tools/brandify.sh`) и запускает Gradle‑задачи в workspace.

## Usage
```bash
cp overlay/config.example.json overlay/config.json   # edit refs/URLs as needed
./overlay/bootstrap.sh                               # creates overlay/workspace/...
./overlay/build.sh                                   # createReleaseDistributable/packageReleasePlatformZip/bundleRelease
# или свои задачи:
./overlay/build.sh bundleRelease assembleRelease
```

Конфиг: один слой `telecrypt-app`, поля `repo`/`ref`/`targetDir`. Кэш bare‑клона лежит в `.overlay/cache/<name>`.
