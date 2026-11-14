# Helm chart: shop

## Установка

```bash
kubectl create namespace shop        # или helm --create-namespace
helm install shop ./helm/shop -n shop --create-namespace
