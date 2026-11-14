#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

BASE_URL="${1:-http://arch.homework}"
COLLECTION="${2:-etc/shop-stream-app.postman_collection.json}"

if ! command -v npx >/dev/null 2>&1; then
  echo "❌ npx не найден. Установите Node.js >=18."; exit 1
fi
mkdir -p reports
TMP_ENV="$(mktemp).json"
cat > "$TMP_ENV" <<JSON
{
  "name": "runtime",
  "values": [ { "key": "baseUrl", "value": "$BASE_URL", "enabled": true } ]
}
JSON

echo "→ Newman against baseUrl=$BASE_URL collection=$COLLECTION"
npx --yes newman run "$COLLECTION" \
  --environment "$TMP_ENV" \
  --reporters cli,json \
  --reporter-json-export "reports/newman-$(date +%Y%m%d-%H%M%S).json" \
  --timeout-request 30000 \
  --delay-request 300

rm -f "$TMP_ENV"
echo "✅ Отчёт в reports/"
