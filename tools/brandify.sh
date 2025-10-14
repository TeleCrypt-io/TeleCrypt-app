#!/usr/bin/env bash
set -euo pipefail

CONFIG_PATH="${1:-branding/branding.json}"

if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "[brandify] config file not found: $CONFIG_PATH" >&2
  exit 1
fi

mapfile -t BRANDING < <(python3 - "$CONFIG_PATH" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f:
    data = json.load(f)
try:
    app_name = data['appName']
    android_app_id = data['androidAppId']
    ios_bundle_id = data.get('iosBundleId', android_app_id)
    icon_dir = data['iconDir']
except KeyError as exc:
    raise SystemExit(f"Missing key in branding config: {exc}")
print(app_name)
print(android_app_id)
print(ios_bundle_id)
print(icon_dir)
PY
)

APP_NAME="${BRANDING[0]}"
ANDROID_APP_ID_RAW="${BRANDING[1]}"
IOS_BUNDLE_ID_RAW="${BRANDING[2]}"
ICON_DIR="${BRANDING[3]}"

trim() {
  local var="$1"
  printf '%s' "$var" |
    tr -d '\r' |
    sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

ANDROID_APP_ID="$(trim "$ANDROID_APP_ID_RAW")"
IOS_BUNDLE_ID="$(trim "$IOS_BUNDLE_ID_RAW")"

SKIP_ANDROID_ID=false
ANDROID_DEV_APP_ID=""
if [[ -z "$ANDROID_APP_ID" ]]; then
  SKIP_ANDROID_ID=true
else
  ANDROID_DEV_APP_ID="${ANDROID_APP_ID}.dev"
fi

if [[ -z "$IOS_BUNDLE_ID" ]]; then
  IOS_BUNDLE_ID="$ANDROID_APP_ID"
fi

PROJECT_SLUG=$(echo "$APP_NAME" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/-\+/-/g' | sed 's/^-//' | sed 's/-$//')
if [[ -z "$PROJECT_SLUG" ]]; then
  PROJECT_SLUG="telecrypt"
fi

if [[ ! -d "$ICON_DIR" ]]; then
  echo "[brandify] icon directory not found: $ICON_DIR" >&2
  exit 1
fi

python3 - <<'PY' "$APP_NAME" "$ANDROID_APP_ID" "$IOS_BUNDLE_ID" "$SKIP_ANDROID_ID" "$PROJECT_SLUG"
import re
from pathlib import Path
import sys

app_name, android_app_id, ios_bundle_id, skip_android_flag, project_slug = sys.argv[1:6]
skip_android = skip_android_flag.lower() == "true" or not android_app_id

replacements = [
    (Path('build.gradle.kts'), r'val appName = "[^"]+"', f'val appName = "{app_name}"'),
    (Path('settings.gradle.kts'), r'rootProject.name = "[^"]+"', f'rootProject.name = "{project_slug}"'),
    (Path('fastlane/Appfile'), r'app_identifier "[^"]+"', f'app_identifier "{ios_bundle_id}"'),
    (Path('iosApp/Configuration/Config.xcconfig'), r'PRODUCT_NAME=.*', f'PRODUCT_NAME={app_name}'),
    (Path('iosApp/Configuration/Config.xcconfig'), r'PRODUCT_BUNDLE_IDENTIFIER=.*', f'PRODUCT_BUNDLE_IDENTIFIER={ios_bundle_id}'),
    (Path('iosApp/iosApp/Info.plist'), r'<string>de.connect2x.tammy</string>', f'<string>{ios_bundle_id}</string>'),
]

if not skip_android:
    replacements.extend([
        (Path('build.gradle.kts'), r'val appIdentifier = "[^"]+"', f'val appIdentifier = "{android_app_id}"'),
        (Path('fastlane/Appfile'), r'package_name "[^"]+"', f'package_name "{android_app_id}"'),
    ])

for path, pattern, replacement in replacements:
    if not path.exists():
        continue
    text = path.read_text(encoding='utf-8')
    new_text = re.sub(pattern, replacement, text)
    if new_text != text:
        path.write_text(new_text, encoding='utf-8')

# flatpak templates marketing copy
def replace_all(path: Path, marker: str, replacement: str):
    if not path.exists():
        return
    text = path.read_text(encoding='utf-8')
    if marker in text:
        path.write_text(text.replace(marker, replacement), encoding='utf-8')

for flatpak_file in [
    Path('flatpak/metainfo.xml.tmpl'),
    Path('flatpak/manifest.json.tmpl'),
    Path('flatpak/app.desktop.tmpl'),
]:
    replace_all(flatpak_file, 'Tammy', app_name)
    if not skip_android:
        replace_all(flatpak_file, 'de.connect2x.tammy', android_app_id)

PY

# google-services package names
if [[ "$SKIP_ANDROID_ID" == true ]]; then
  echo "[brandify] androidAppId missing, skipping google-services replacement"
else
python3 - <<'PY' "$ANDROID_APP_ID" "$ANDROID_DEV_APP_ID"
import json
from pathlib import Path
import sys

prod, dev = sys.argv[1:3]
path = Path('google-services.json')
if not path.exists():
    sys.exit()
with path.open(encoding='utf-8') as f:
    data = json.load(f)
for client in data.get('client', []):
    info = client.get('client_info', {})
    pkg = info.get('android_client_info', {}).get('package_name')
    if pkg and pkg.endswith('.dev'):
        info['android_client_info']['package_name'] = dev
    elif pkg:
        info['android_client_info']['package_name'] = prod
path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding='utf-8')
PY

# Update Android Manifest label if hardcoded elsewhere (string resource handled via appName)
python3 - <<'PY' "$ANDROID_APP_ID"
from pathlib import Path
import sys
app_id = sys.argv[1]
manifest = Path('src/androidMain/AndroidManifest.xml')
if manifest.exists():
    text = manifest.read_text(encoding='utf-8')
    new_text = text.replace('de.connect2x.tammy', app_id)
    if new_text != text:
        manifest.write_text(new_text, encoding='utf-8')
PY

fi

# Copy icons
ANDROID_TARGET="src/androidMain/res"
IOS_TARGET="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"
DESKTOP_TARGET="src/desktopMain/resources"

copy_tree() {
  local source="$1"
  local dest="$2"
  if [[ -d "$source" ]]; then
    mkdir -p "$dest"
    cp -R "$source"/. "$dest"/
  fi
}

if [[ "$SKIP_ANDROID_ID" != true ]]; then
  if [[ -d "$ANDROID_TARGET" ]]; then
    find "$ANDROID_TARGET" -type f -name 'ic_launcher*.png' -delete
    find "$ANDROID_TARGET" -type f -name 'ic_launcher*.webp' -delete
  fi
  copy_tree "$ICON_DIR/android" "$ANDROID_TARGET"
fi
copy_tree "$ICON_DIR/ios/AppIcon.appiconset" "$IOS_TARGET"
copy_tree "$ICON_DIR/desktop" "$DESKTOP_TARGET"

# Desktop MSIX assets
if [[ -d "$ICON_DIR/desktop-msix" ]]; then
  copy_tree "$ICON_DIR/desktop-msix" "build/compose/binaries/main-release/msix"
fi

echo "[brandify] Applied branding for $APP_NAME ($ANDROID_APP_ID / $IOS_BUNDLE_ID)"
