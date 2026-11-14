#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

kubectl apply -f namespace.yaml
kubectl apply -f common-configmap.yaml
kubectl apply -f common-secrets.yaml

echo "→ postgres..."
kubectl apply -f postgres.yaml
kubectl rollout status statefulset/postgres -n shop --timeout=120s
kubectl wait --for=condition=complete job/postgres-init-dbs -n shop --timeout=120s || true

echo "→ kafka..."
kubectl apply -f kafka.yaml
kubectl rollout status statefulset/kafka -n shop --timeout=180s

echo "→ services..."
kubectl apply -f user-service/, billing-service/, order-service/, notification-service/

echo "→ ingress"
kubectl apply -f ingress.yaml

echo
kubectl get pods -n shop
kubectl get ingress -n shop
