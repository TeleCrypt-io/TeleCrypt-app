#!/usr/bin/env bash
set -euo pipefail

# associative array to track visited refs
declare -A visited

traverse() {
    read -r ref commit <<< "$(flatpak info "$1" --show-ref --show-commit)"

    if [[ -n "${visited[$ref]:-}" ]]; then return; fi
    visited[$ref]=1

    echo "    \"${ref}\" to \"${commit}\","

    local deps=$(flatpak info --show-extensions "$ref" | awk '/^ *Extension:/ {print $2}' || true)

    while IFS= read -r ext; do
        [[ -z "$ext" ]] && continue
        traverse "$ext"
    done <<< "$deps"
}

echo "mapOf("
traverse org.freedesktop.Sdk
traverse org.freedesktop.Platform
echo ")"
