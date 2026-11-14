#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

BASE_URL="${1:-http://arch.homework}"   # ← default как в ДЗ
ENV_FILE="${2:-etc/shop-stream-app.postman_environment.json}"

if ! command -v npx >/dev/null 2>&1; then
  echo "❌ npx не найден. Установите Node.js >=18."
  exit 1
fi

mkdir -p reports

# Генерируем временный env с baseUrl
TMP_ENV="$(mktemp).json"
cat > "$TMP_ENV" <<EOF
{
  "name": "runtime",
  "values": [
    { "key": "baseUrl", "value": "$BASE_URL", "enabled": true }
  ]
}
EOF

echo "→ Newman against baseUrl=$BASE_URL"
npx --yes newman run etc/shop-stream-app.postman_collection.json \
  --environment "$TMP_ENV" \
  --reporters cli,json \
  --reporter-json-export "reports/newman-$(date +%Y%m%d-%H%M%S).json" \
  --timeout-request 30000 \
  --delay-request 500

rm -f "$TMP_ENV"
echo "✅ Отчёт в reports/"