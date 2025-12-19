#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

LOG_FILE="iosApp/build/embed.log"
mkdir -p "$(dirname "${LOG_FILE}")"
exec > >(tee -a "${LOG_FILE}")
exec 2>&1

CONFIGURATION="${CONFIGURATION:-Release}"
SDK_NAME="${SDK_NAME:-iphoneos}"
TARGET_BUILD_DIR="${TARGET_BUILD_DIR:-}"
FRAMEWORKS_FOLDER_PATH="${FRAMEWORKS_FOLDER_PATH:-}"
DEST_DIR=""
if [[ -n "${TARGET_BUILD_DIR}" && -n "${FRAMEWORKS_FOLDER_PATH}" ]]; then
  DEST_DIR="${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}"
fi

PREBUILT_DIRS=(
  "build/xcode-frameworks"
)

has_prebuilt=false
for dir in "${PREBUILT_DIRS[@]}"; do
  if [[ -d "${dir}/${CONFIGURATION}/${SDK_NAME}" ]] && ls -A "${dir}/${CONFIGURATION}/${SDK_NAME}" >/dev/null 2>&1; then
    has_prebuilt=true
    break
  fi
done

CI_FLAG="${CI_SKIP_GRADLE_EMBED:-<unset>}"
echo "::notice::[embed] diag CI_SKIP_GRADLE_EMBED=${CI_FLAG} CONFIG=${CONFIGURATION} SDK=${SDK_NAME} PREBUILT=${has_prebuilt}"

copy_from_dir() {
  local base="$1"
  local src="${base}/${CONFIGURATION}/${SDK_NAME}"
  if [[ ! -d "${src}" ]]; then
    echo "::warning::[embed] prebuilt dir missing: ${src}"
    return 1
  fi
  if [[ -z "${DEST_DIR}" ]]; then
    echo "::error::[embed] TARGET_BUILD_DIR/FRAMEWORKS_FOLDER_PATH not provided"
    return 1
  fi
  mkdir -p "${DEST_DIR}"
  local rsync_flags=(-a --exclude '*.dSYM' --exclude '*.dSYM/' --exclude '*/Contents/Resources/DWARF/*')
  rsync "${rsync_flags[@]}" "${src}/" "${DEST_DIR}/"
}

codesign_frameworks() {
  if [[ -z "${DEST_DIR}" || -z "${EXPANDED_CODE_SIGN_IDENTITY:-}" || "${EXPANDED_CODE_SIGN_IDENTITY}" == "-" ]]; then
    return 0
  fi
  find "${DEST_DIR}" -maxdepth 1 -name "*.framework" -print0 | while IFS= read -r -d '' fw; do
    echo "::notice::[embed] codesign ${fw##*/}"
    /usr/bin/codesign --force --sign "${EXPANDED_CODE_SIGN_IDENTITY}" --preserve-metadata=identifier,entitlements "${fw}"
  done
}

copy_all_prebuilt() {
  local ok=true
  for dir in "${PREBUILT_DIRS[@]}"; do
    copy_from_dir "${dir}" || ok=false
  done
  if ${ok}; then
    codesign_frameworks
    return 0
  fi
  return 1
}

if [[ "${CI_SKIP_GRADLE_EMBED:-}" == "true" && "${has_prebuilt}" == "true" ]]; then
  if copy_all_prebuilt; then
    echo "::notice::[embed] reused prebuilt frameworks"
    exit 0
  else
    echo "::warning::[embed] failed to reuse prebuilt frameworks, falling back to Gradle"
  fi
elif [[ "${CI_SKIP_GRADLE_EMBED:-}" == "true" && "${has_prebuilt}" != "true" ]]; then
  echo "::warning::[embed] skip requested but no prebuilt frameworks found"
fi

if [[ "${CI_SKIP_GRADLE_EMBED:-}" == "true" && "${CI:-}" == "true" ]]; then
  echo "::error::[embed] refusing to rerun Gradle on CI"
  echo "::group::[embed] current xcode-frameworks layout"
  if [ -d build/xcode-frameworks ]; then
    find build/xcode-frameworks -maxdepth 3 -type d
  elif [ -d build ]; then
    find build -maxdepth 3 -type d
  else
    echo "build directory missing"
  fi
  echo "::endgroup::"
  exit 1
fi

echo "::notice::[embed] start $(date)"
/usr/bin/env time -l ./gradlew :embedAndSignAppleFrameworkForXcode
STATUS=$?
echo "::notice::[embed] end $(date) status=${STATUS}"
exit ${STATUS}
