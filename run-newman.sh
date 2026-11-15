#!/usr/bin/env bash
# run-newman.sh — E2E-прогон Postman-коллекций через Newman.
#
# Использование:
#   ./run-newman.sh                          # stream-app, baseUrl=http://arch.homework
#   ./run-newman.sh http://localhost:8080    # stream-app против Docker Compose
#   ./run-newman.sh --saga                   # saga-коллекция, baseUrl=http://arch.homework
#   ./run-newman.sh --saga http://localhost:8080
#   ./run-newman.sh --saga --both            # прогнать обе коллекции подряд
#   ./run-newman.sh --help
#
# Опции:
#   --saga        использовать etc/shop-saga.postman_collection.json
#   --stream      использовать etc/shop-stream-app.postman_collection.json (по умолчанию)
#   --both        прогнать обе коллекции (stream, затем saga)
#   --no-json     не писать JSON-отчёт (только CLI)
#   --help        эта справка

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPORTS_DIR="$ROOT/reports"
STREAM_COLL="$ROOT/etc/shop-stream-app.postman_collection.json"
SAGA_COLL="$ROOT/etc/shop-saga.postman_collection.json"

BASE_URL="http://arch.homework"
COLLECTION="stream"
BOTH=false
WRITE_JSON=true

# ---------- парсинг аргументов ----------
for arg in "$@"; do
  case "$arg" in
    --saga)    COLLECTION="saga" ;;
    --stream)  COLLECTION="stream" ;;
    --both)    BOTH=true ;;
    --no-json) WRITE_JSON=false ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -20
      exit 0 ;;
    http://*|https://*)
      BASE_URL="$arg" ;;
    *)
      echo "Неизвестный аргумент: $arg" >&2
      echo "См. $0 --help" >&2
      exit 2 ;;
  esac
done

# ---------- утилиты ----------
log()  { printf '\033[1;34m→\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

# ---------- проверка npx ----------
if ! command -v npx >/dev/null 2>&1; then
  err "npx не найден. Установите Node.js ≥18: https://nodejs.org/"
  exit 1
fi

NODE_VERSION="$(node -v 2>/dev/null || echo "")"
if [[ -z "$NODE_VERSION" ]]; then
  err "node не найден. Установите Node.js ≥18."
  exit 1
fi
log "Node.js: $NODE_VERSION"

# ---------- проверка коллекций ----------
check_collection() {
  local f="$1"
  if [[ ! -f "$f" ]]; then
    err "Коллекция не найдена: $f"
    exit 1
  fi
}

# ---------- подготовка env ----------
mkdir -p "$REPORTS_DIR"

TMP_ENV="$(mktemp -t newman-env-XXXXXX.json)"
cleanup() { rm -f "$TMP_ENV"; }
trap cleanup EXIT

cat > "$TMP_ENV" <<EOF
{
  "name": "newman-temp",
  "values": [
    { "key": "baseUrl", "value": "${BASE_URL}", "enabled": true }
  ],
  "_postman_variable_scope": "environment"
}
EOF

# ---------- прогон одной коллекции ----------
# usage: run_one <collection-file> <label>
run_one() {
  local coll="$1"
  local label="$2"
  local ts; ts="$(date +%Y%m%d-%H%M%S)"
  local json_report="$REPORTS_DIR/newman-${label}-${ts}.json"

  log "Newman: $label"
  log "  collection: $coll"
  log "  baseUrl:    $BASE_URL"

  local reporters="cli"
  local reporter_args=()
  if $WRITE_JSON; then
    reporters="cli,json"
    reporter_args=(--reporter-json-export "$json_report")
  fi

  local rc=0
  npx --yes newman run "$coll" \
    --environment "$TMP_ENV" \
    --timeout-request 30000 \
    --delay-request 500 \
    --reporters "$reporters" \
    "${reporter_args[@]}" \
    || rc=$?

  if [[ $rc -eq 0 ]]; then
    ok "Newman: $label — успешно"
    if $WRITE_JSON; then
      ok "  отчёт: $json_report"
    fi
  else
    err "Newman: $label — провал (exit $rc)"
    if $WRITE_JSON && [[ -f "$json_report" ]]; then
      err "  отчёт: $json_report"
      # краткая сводка из JSON (если есть python3)
      if command -v python3 >/dev/null 2>&1; then
        python3 - "$json_report" <<'PY' || true
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
stats = d.get("run", {}).get("stats", {})
fails = d.get("run", {}).get("failures", []) or []
print(f"  requests={stats.get('requests',{}).get('total',0)} "
      f"failed={stats.get('requests',{}).get('failed',0)} "
      f"assertions={stats.get('assertions',{}).get('total',0)} "
      f"failed={stats.get('assertions',{}).get('failed',0)}")
for f in fails[:5]:
    src = f.get("source", {})
    err = f.get("error", {})
    print(f"  ✗ {src.get('name','?')}: {err.get('message','?')}")
PY
      fi
    fi
    return $rc
  fi
}

# ---------- основной сценарий ----------
fail=0

if $BOTH; then
  check_collection "$STREAM_COLL"
  check_collection "$SAGA_COLL"
  run_one "$STREAM_COLL" "stream" || fail=$((fail+1))
  echo
  run_one "$SAGA_COLL"   "saga"   || fail=$((fail+1))
else
  if [[ "$COLLECTION" == "saga" ]]; then
    check_collection "$SAGA_COLL"
    run_one "$SAGA_COLL" "saga" || fail=$((fail+1))
  else
    check_collection "$STREAM_COLL"
    run_one "$STREAM_COLL" "stream" || fail=$((fail+1))
  fi
fi

echo
if [[ $fail -eq 0 ]]; then
  ok "Все прогоны успешны"
  exit 0
else
  err "Провалов: $fail"
  exit 1
fi