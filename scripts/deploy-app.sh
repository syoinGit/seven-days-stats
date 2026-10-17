#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_NAME="${SERVICE_NAME:-}"

cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  echo "Missing .env: $ROOT_DIR/.env" >&2
  exit 1
fi

# Use the same shell syntax and precedence as run-app.sh. Keep secrets in the
# subshell rather than exporting them to build and deployment commands.
HEALTHCHECK_PORT="$(source .env || exit; printf '%s' "${SERVER_PORT:-8082}")"
if [[ ! "$HEALTHCHECK_PORT" =~ ^[0-9]+$ ]]; then
  echo "SERVER_PORT must be numeric." >&2
  exit 1
fi
HEALTHCHECK_URL="http://127.0.0.1:${HEALTHCHECK_PORT}/"
CURRENT_JAR="$ROOT_DIR/app/app.jar"
PREVIOUS_JAR="$ROOT_DIR/app/app.previous.jar"
HAS_PREVIOUS_JAR=false

if [[ -z "$SERVICE_NAME" ]]; then
  for candidate in rubinco.service rubinco-watchpoint.service seven-days-stats.service sevendays-states.service; do
    if systemctl cat "$candidate" >/dev/null 2>&1; then
      SERVICE_NAME="$candidate"
      break
    fi
  done
fi

if [[ -z "$SERVICE_NAME" ]]; then
  echo "No Rubinco systemd service found." >&2
  echo "Set SERVICE_NAME explicitly and run this script again." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "Tracked or untracked files contain local changes; deployment aborted." >&2
  exit 1
fi

git pull --ff-only

if [[ -s "$CURRENT_JAR" ]]; then
  cp -p "$CURRENT_JAR" "$PREVIOUS_JAR"
  HAS_PREVIOUS_JAR=true
fi

./scripts/build-app.sh

rollback() {
  if [[ "$HAS_PREVIOUS_JAR" != true ]]; then
    echo "No previous application jar is available for rollback." >&2
    return
  fi
  echo "Restoring the previous application jar." >&2
  cp -p "$PREVIOUS_JAR" "$ROOT_DIR/app/.app.jar.rollback.tmp"
  mv "$ROOT_DIR/app/.app.jar.rollback.tmp" "$CURRENT_JAR"
  sudo systemctl restart "$SERVICE_NAME" || true
}

if ! sudo systemctl restart "$SERVICE_NAME"; then
  rollback
  exit 1
fi

for attempt in {1..60}; do
  if curl --fail --silent --connect-timeout 2 --max-time 5 --output /dev/null "$HEALTHCHECK_URL"; then
    break
  fi
  if ! sudo systemctl is-active --quiet "$SERVICE_NAME"; then
    sudo systemctl status "$SERVICE_NAME" --no-pager >&2 || true
    rollback
    exit 1
  fi
  sleep 2
done

if ! curl --fail --silent --connect-timeout 2 --max-time 5 --output /dev/null "$HEALTHCHECK_URL"; then
  sudo journalctl -u "$SERVICE_NAME" -n 100 --no-pager >&2 || true
  rollback
  exit 1
fi

sudo systemctl status "$SERVICE_NAME" --no-pager
