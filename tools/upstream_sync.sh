#!/usr/bin/env bash
set -euo pipefail

DEFAULT_BRANCH="main"
UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_URL="${UPSTREAM_URL:-https://gitlab.com/connect2x/tammy.git}"
BRANDING_CONFIG="${BRANDING_CONFIG:-branding/branding.json}"
BRANDIFY_SCRIPT="${BRANDIFY_SCRIPT:-tools/brandify.sh}"
PUSH_UPDATES_DEFAULT="${PUSH_UPDATES:-}"
AUTO_COMMIT_DEFAULT="${AUTO_COMMIT:-}"
ALLOW_REBASE_DEFAULT="${ALLOW_REBASE:-}"
BRANDING_COMMIT_MESSAGE="${BRANDING_COMMIT_MESSAGE:-chore: apply TeleCrypt branding}"

info() {
  printf '[upstream_sync] %s\n' "$*"
}

abort() {
  printf '[upstream_sync] %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: tools/upstream_sync.sh [options] [branch]

Options:
  --auto-commit       автоматически коммитить изменения после brandify
  --no-auto-commit    оставить изменения после brandify неподтверждёнными
  --push              выполнять git push (поведение по умолчанию)
  --no-push           пропустить git push
  --rebase            пробовать git rebase, если fast-forward невозможен (по умолчанию)
  --no-rebase         прерываться, если fast-forward невозможен
  -h, --help          показать эту справку

Также можно использовать переменные окружения:
  AUTO_COMMIT, PUSH_UPDATES, ALLOW_REBASE (true/false/yes/no).
EOF
}

normalize_bool() {
  local value="${1:-}"
  case "${value,,}" in
    "" ) echo "" ;;
    true|t|yes|y|1|on ) echo "true" ;;
    false|f|no|n|0|off ) echo "false" ;;
    *)
      abort "invalid boolean value: $value"
      ;;
  esac
}

prompt_bool() {
  local question="$1"
  local default="$2"
  local hint reply
  if [[ "${default,,}" == "true" ]]; then
    hint="Y/n"
  else
    hint="y/N"
  fi
  while true; do
    printf '[upstream_sync] %s [%s] ' "$question" "$hint"
    IFS= read -r reply || true
    reply="${reply%%$'\r'}"
    reply="${reply#"${reply%%[![:space:]]*}"}"
    reply="${reply%"${reply##*[![:space:]]}"}"
    if [[ -z "$reply" ]]; then
      echo "$default"
      return 0
    fi
    case "${reply,,}" in
      y|yes) echo "true"; return 0 ;;
      n|no) echo "false"; return 0 ;;
    esac
    printf '[upstream_sync] Пожалуйста, ответьте y или n.\n' >&2
  done
}

CLI_AUTO_COMMIT=""
CLI_PUSH_UPDATES=""
CLI_ALLOW_REBASE=""
branch_set=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --auto-commit)
      CLI_AUTO_COMMIT="true"
      shift
      ;;
    --no-auto-commit)
      CLI_AUTO_COMMIT="false"
      shift
      ;;
    --push)
      CLI_PUSH_UPDATES="true"
      shift
      ;;
    --no-push)
      CLI_PUSH_UPDATES="false"
      shift
      ;;
    --rebase)
      CLI_ALLOW_REBASE="true"
      shift
      ;;
    --no-rebase)
      CLI_ALLOW_REBASE="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      abort "unknown option: $1"
      ;;
    *)
      if [[ "$branch_set" == "false" ]]; then
        DEFAULT_BRANCH="$1"
        branch_set=true
        shift
      else
        abort "multiple branch arguments provided"
      fi
      ;;
  esac
done

if [[ $# -gt 0 ]]; then
  if [[ "$branch_set" == "false" && $# -eq 1 ]]; then
    DEFAULT_BRANCH="$1"
    shift
  else
    abort "unexpected arguments: $*"
  fi
fi

if [[ $# -gt 0 ]]; then
  abort "unexpected arguments: $*"
fi

is_interactive=false
if [[ -t 0 && -t 1 ]]; then
  is_interactive=true
fi

AUTO_COMMIT="${CLI_AUTO_COMMIT:-}"
if [[ -z "$AUTO_COMMIT" ]]; then
  AUTO_COMMIT="$(normalize_bool "$AUTO_COMMIT_DEFAULT")"
fi
if [[ -z "$AUTO_COMMIT" ]]; then
  if [[ "$is_interactive" == true ]]; then
    AUTO_COMMIT="$(prompt_bool "Автоматически закоммитить изменения после brandify?" "true")"
  else
    AUTO_COMMIT="true"
  fi
fi

PUSH_UPDATES="${CLI_PUSH_UPDATES:-}"
if [[ -z "$PUSH_UPDATES" ]]; then
  PUSH_UPDATES="$(normalize_bool "$PUSH_UPDATES_DEFAULT")"
fi
if [[ -z "$PUSH_UPDATES" ]]; then
  if [[ "$is_interactive" == true ]]; then
    PUSH_UPDATES="$(prompt_bool "Выполнить git push в origin/${DEFAULT_BRANCH}?" "true")"
  else
    PUSH_UPDATES="true"
  fi
fi

ALLOW_REBASE="${CLI_ALLOW_REBASE:-}"
if [[ -z "$ALLOW_REBASE" ]]; then
  ALLOW_REBASE="$(normalize_bool "$ALLOW_REBASE_DEFAULT")"
fi
if [[ -z "$ALLOW_REBASE" ]]; then
  if [[ "$is_interactive" == true ]]; then
    ALLOW_REBASE="$(prompt_bool "Пробовать git rebase, если fast-forward невозможен?" "true")"
  else
    ALLOW_REBASE="true"
  fi
fi

info "branch: $DEFAULT_BRANCH, auto_commit: $AUTO_COMMIT, push: $PUSH_UPDATES, rebase: $ALLOW_REBASE"

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
rebased=false
if git merge --ff-only "$UPSTREAM_REMOTE/$DEFAULT_BRANCH"; then
  info "fast-forward successful"
else
  if [[ "${ALLOW_REBASE,,}" != "true" ]]; then
    abort "fast-forward merge failed and automatic rebase is disabled; resolve conflicts manually and rerun"
  fi
  info "fast-forward not possible, attempting rebase onto $UPSTREAM_REMOTE/$DEFAULT_BRANCH"
  if git rebase "$UPSTREAM_REMOTE/$DEFAULT_BRANCH"; then
    rebased=true
    info "rebase completed successfully"
  else
    git rebase --abort || true
    abort "rebase failed; resolve conflicts manually and rerun"
  fi
fi

if [[ -x "$BRANDIFY_SCRIPT" && -f "$BRANDING_CONFIG" ]]; then
  info "reapplying branding via $BRANDIFY_SCRIPT"
  "$BRANDIFY_SCRIPT" "$BRANDING_CONFIG"
else
  info "branding script or config missing; skip rebranding"
fi

if [[ -n "$(git status --porcelain)" ]]; then
  if [[ "${AUTO_COMMIT,,}" == "true" ]]; then
    info "branding produced changes; committing"
    git add -A
    git commit -m "$BRANDING_COMMIT_MESSAGE"
    git status -sb
  else
    info "changes after sync:"
    git status -sb
  fi
else
  info "no changes detected after sync"
fi

if [[ "${PUSH_UPDATES,,}" == "true" ]]; then
  info "pushing $DEFAULT_BRANCH to origin"
  if [[ "$rebased" == true ]]; then
    git push --force-with-lease origin "$DEFAULT_BRANCH"
  else
    git push origin "$DEFAULT_BRANCH"
  fi
else
  info "PUSH_UPDATES=$PUSH_UPDATES, skipping git push"
fi
