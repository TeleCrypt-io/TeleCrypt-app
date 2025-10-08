#!/usr/bin/env bash
set -euo pipefail

DEFAULT_BRANCH="${1:-main}"
UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_URL="${UPSTREAM_URL:-https://gitlab.com/connect2x/tammy.git}"
BRANDING_CONFIG="${BRANDING_CONFIG:-branding/branding.json}"
BRANDIFY_SCRIPT="${BRANDIFY_SCRIPT:-tools/brandify.sh}"
PUSH_UPDATES="${PUSH_UPDATES:-true}"

info() {
  printf '[upstream_sync] %s\n' "$*"
}

abort() {
  printf '[upstream_sync] %s\n' "$*" >&2
  exit 1
}

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  abort "not inside a git repository"
fi

if [[ -n "$(git status --porcelain)" ]]; then
  abort "working tree is dirty; stash or commit your changes before syncing"
fi

if ! git remote get-url "$UPSTREAM_REMOTE" >/dev/null 2>&1; then
  info "adding remote '$UPSTREAM_REMOTE' -> $UPSTREAM_URL"
  git remote add "$UPSTREAM_REMOTE" "$UPSTREAM_URL"
fi

info "fetching '$UPSTREAM_REMOTE' and 'origin'"
git fetch "$UPSTREAM_REMOTE" --tags
git fetch origin --tags

if git show-ref --verify --quiet "refs/heads/$DEFAULT_BRANCH"; then
  git switch "$DEFAULT_BRANCH"
else
  info "creating local branch '$DEFAULT_BRANCH' from origin/$DEFAULT_BRANCH"
  git switch --create "$DEFAULT_BRANCH" "origin/$DEFAULT_BRANCH"
fi

if [[ -n "$(git status --porcelain)" ]]; then
  abort "branch '$DEFAULT_BRANCH' has local modifications; aborting merge"
fi

info "fast-forward merging $UPSTREAM_REMOTE/$DEFAULT_BRANCH into $DEFAULT_BRANCH"
git merge --ff-only "$UPSTREAM_REMOTE/$DEFAULT_BRANCH"

if [[ -x "$BRANDIFY_SCRIPT" && -f "$BRANDING_CONFIG" ]]; then
  info "reapplying branding via $BRANDIFY_SCRIPT"
  "$BRANDIFY_SCRIPT" "$BRANDING_CONFIG"
else
  info "branding script or config missing; skip rebranding"
fi

if [[ -n "$(git status --porcelain)" ]]; then
  info "changes after sync:"
  git status -sb
else
  info "no changes detected after sync"
fi

if [[ "${PUSH_UPDATES,,}" == "true" ]]; then
  info "pushing $DEFAULT_BRANCH to origin"
  git push origin "$DEFAULT_BRANCH"
else
  info "PUSH_UPDATES=$PUSH_UPDATES, skipping git push"
fi
