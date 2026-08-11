#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
JAR_FILE="$ROOT_DIR/app/app.jar"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing .env: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
  echo "Missing jar: $JAR_FILE" >&2
  exit 1
fi

cd "$ROOT_DIR"
set -a
source "$ENV_FILE"
set +a

exec java -jar "$JAR_FILE" --spring.profiles.active="${SPRING_PROFILES_ACTIVE:-production}"
