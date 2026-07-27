#!/usr/bin/env bash
# Re-applies TeleCrypt branding after an upstream merge (or in CI).
# Data-driven from branding/branding.json via tools/apply_branding.py.
set -euo pipefail

CONFIG_PATH="${1:-branding/branding.json}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "[post_merge] config file not found: $CONFIG_PATH" >&2
  exit 1
fi

PYTHON_BIN=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1; then
    PYTHON_BIN="$candidate"
    break
  fi
done
if [[ -z "$PYTHON_BIN" ]]; then
  echo "[post_merge] python3 or python executable not found" >&2
  exit 1
fi

"$PYTHON_BIN" "$SCRIPT_DIR/apply_branding.py" post "$CONFIG_PATH"

# Copy launcher icons (best-effort; skip if the icon directory is missing).
ICON_DIR="$(python3 - "$CONFIG_PATH" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
print(data.get("iconDir", ""))
PY
)"
if [[ -n "$ICON_DIR" && -d "$ICON_DIR" ]]; then
  copy_tree() {
    local source="$1" dest="$2"
    if [[ -d "$source" ]]; then
      mkdir -p "$dest"
      cp -R "$source"/. "$dest"/
    fi
  }
  copy_tree "$ICON_DIR/android" "src/androidMain/res"
  copy_tree "$ICON_DIR/ios/AppIcon.appiconset" "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"
  copy_tree "$ICON_DIR/desktop" "src/desktopMain/resources"
fi

echo "[post_merge] TeleCrypt branding applied from $CONFIG_PATH"
