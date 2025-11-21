#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${1:-overlay/config.json}"
EXAMPLE_FILE="overlay/config.example.json"
CACHE_DIR=".overlay/cache"
WORKSPACE_DIR="overlay/workspace"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  if [[ -f "${EXAMPLE_FILE}" ]]; then
    echo "[overlay] Config '${CONFIG_FILE}' not found. Copying example..."
    cp "${EXAMPLE_FILE}" "${CONFIG_FILE}"
    echo "[overlay] Please adjust '${CONFIG_FILE}' with repo URLs and refs, then rerun."
    exit 1
  else
    echo "[overlay] Config '${CONFIG_FILE}' not found and no example available" >&2
    exit 1
  fi
fi

mkdir -p "${CACHE_DIR}" "${WORKSPACE_DIR}"

readarray -t LAYERS < <(CONFIG_JSON="${CONFIG_FILE}" python3 <<'PY'
import json, os, pathlib
config_path = pathlib.Path(os.environ["CONFIG_JSON"])
with config_path.open(encoding='utf-8') as f:
    data = json.load(f)
for layer in data.get('layers', []):
    name = layer['name']
    repo = layer['repo']
    ref = layer['ref']
    target = layer['targetDir']
    print(f"{name}\t{repo}\t{ref}\t{target}")
PY
)

if [[ ${#LAYERS[@]} -eq 0 ]]; then
  echo "[overlay] No layers defined in ${CONFIG_FILE}" >&2
  exit 1
fi

printf '\n[overlay] Preparing layers...\n'

for entry in "${LAYERS[@]}"; do
  IFS=$'\t' read -r NAME REPO REF TARGET <<<"${entry}"
  CACHE_PATH="${CACHE_DIR}/${NAME}"
  TARGET_PATH="${WORKSPACE_DIR}/${TARGET}"

  echo "[overlay] === ${NAME} (${REF}) ==="
  if [[ ! -d "${CACHE_PATH}/.git" ]]; then
    echo "[overlay] Cloning ${REPO} -> ${CACHE_PATH}"
    git clone --filter=blob:none --quiet "${REPO}" "${CACHE_PATH}"
  fi

  echo "[overlay] Fetching updates"
  git -C "${CACHE_PATH}" fetch --prune --tags --quiet

  if git -C "${CACHE_PATH}" rev-parse --verify --quiet "${REF}" >/dev/null; then
    git -C "${CACHE_PATH}" checkout --quiet "${REF}"
  else
    git -C "${CACHE_PATH}" checkout --quiet FETCH_HEAD
  fi
  git -C "${CACHE_PATH}" reset --hard --quiet "${REF}"

  SHA=$(git -C "${CACHE_PATH}" rev-parse HEAD)
  echo "[overlay] Checked out ${SHA}"

  echo "[overlay] Syncing to workspace/${TARGET}"
  rsync -a --delete --exclude '.git' "${CACHE_PATH}/" "${TARGET_PATH}/"
  echo "[overlay] Ready: ${TARGET_PATH}"
  echo

done

echo "[overlay] Done. Sources available under '${WORKSPACE_DIR}'."
