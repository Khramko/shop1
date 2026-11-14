#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
kubectl delete -f ingress.yaml --ignore-not-found
kubectl delete -f user-service/ billing-service/ order-service/ notification-service/ warehouse-service/ delivery-service/ --ignore-not-found
kubectl delete -f kafka.yaml    --ignore-not-found
kubectl delete -f postgres.yaml --ignore-not-found
kubectl delete -f common-secrets.yaml common-configmap.yaml --ignore-not-found
kubectl delete -f namespace.yaml --ignore-not-found
echo "✅ Shop uninstalled"
