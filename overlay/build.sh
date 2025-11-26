#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_DIR="overlay/workspace/layers/telecrypt-app"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRANDIFY_CONFIG="${ROOT_DIR}/branding/branding.json"
BRANDIFY_SCRIPT="${ROOT_DIR}/tools/brandify.sh"
DEFAULT_TASKS=("createReleaseDistributable" "packageReleasePlatformZip" "bundleRelease")

if [[ ! -d "${WORKSPACE_DIR}" ]]; then
  echo "[overlay] Workspace not found: ${WORKSPACE_DIR}. Run bootstrap first." >&2
  exit 1
fi

pushd "${WORKSPACE_DIR}" >/dev/null

if [[ -f "${BRANDIFY_CONFIG}" && -x "${BRANDIFY_SCRIPT}" ]]; then
  echo "[overlay] Applying branding from ${BRANDIFY_CONFIG}"
  "${BRANDIFY_SCRIPT}" "${BRANDIFY_CONFIG}"
else
  echo "[overlay] Branding skipped: missing ${BRANDIFY_CONFIG} or ${BRANDIFY_SCRIPT}"
fi

if [[ $# -gt 0 ]]; then
  TASKS=("$@")
else
  TASKS=("${DEFAULT_TASKS[@]}")
fi

./gradlew --stacktrace "${TASKS[@]}"

popd >/dev/null
