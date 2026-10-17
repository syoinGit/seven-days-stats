#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p app
./mvnw clean package
cp target/rubinco.jar app/.app.jar.tmp
mv app/.app.jar.tmp app/app.jar

echo "Built app/app.jar"
