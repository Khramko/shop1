#!/usr/bin/env bash
# shop/build-all.sh
set -euo pipefail

TAG="${1:-dev}"

for MODULE in user-service billing-service order-service notification-service; do
  echo "▶ Building shop/${MODULE}:${TAG}"
  docker build \
    --build-arg MODULE="${MODULE}" \
    -t "shop/${MODULE}:${TAG}" \
    -f Dockerfile .
done

echo "✅ All images built locally (no push). Tag = ${TAG}"