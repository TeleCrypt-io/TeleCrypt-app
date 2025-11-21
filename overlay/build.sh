#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_DIR="overlay/workspace/layers/telecrypt-app"
if [[ ! -d "${WORKSPACE_DIR}" ]]; then
  echo "[overlay] Workspace not found: ${WORKSPACE_DIR}. Run bootstrap first." >&2
  exit 1
fi

pushd "${WORKSPACE_DIR}" >/dev/null

./gradlew --stacktrace createReleaseDistributable packageReleasePlatformZip bundleRelease

popd >/dev/null
