#!/usr/bin/env bash
# Reverts branded files to upstream Tammy state BEFORE an upstream merge.
# Data-driven from branding/upstream.json via tools/apply_branding.py.
#
# This prevents merge conflicts on branded lines: run this, then
# `git merge upstream/main`, then `tools/post_merge.sh` to re-apply branding.
set -euo pipefail

CONFIG_PATH="${1:-branding/upstream.json}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "[pre_merge] config file not found: $CONFIG_PATH" >&2
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
  echo "[pre_merge] python3 or python executable not found" >&2
  exit 1
fi

"$PYTHON_BIN" "$SCRIPT_DIR/apply_branding.py" pre "$CONFIG_PATH"

echo "[pre_merge] branded files reverted to upstream state from $CONFIG_PATH"
