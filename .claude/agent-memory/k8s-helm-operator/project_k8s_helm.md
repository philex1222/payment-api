---
name: payment-api Kubernetes and Helm configuration
description: Namespaces, Helm chart path, secret names, image registry, and CD workflow details for payment-api K8s deployment
type: project
---

Helm chart lives at `helm/payment-api/` (chart name `payment-api`, release name `payment-api`).
Base K8s manifests (namespace, configmap, secret templates) live at `k8s/`.

**Namespaces:** `payment-staging` and `payment-prod` (both labelled with restricted pod-security policy).

**Image registry:** `ghcr.io/philex1222/payment-api` — images are pushed by the CD workflow with tag `sha-<7-char-sha>`. Never use `latest` in staging/prod.

**Required secrets** (created out-of-band before first Helm install):
- `payment-api-db-secret` — keys: `url`, `username`, `password`
- `payment-api-jwt-secret` — key: `secret` (must be >= 64 chars for HS512)
- `ghcr-pull-secret` — docker-registry type, for GHCR pull in staging/prod

**ConfigMap name:** `payment-api-config` (rendered by Helm from `values.configMap.data`).

**Environment-specific values files:**
- `values-dev.yaml` — inline creds, relaxed resources (256Mi/100m req), debug logging, no HPA
- `values-staging.yaml` — 2 replicas, HPA (2–4), PDB minAvailable=1, letsencrypt-staging TLS
- `values-prod.yaml` — 3 replicas, HPA (3–10), PDB minAvailable=2, zone topology spread, pod anti-affinity

**Why:** CD workflow previously had TODO placeholder deploy steps. Helm was introduced to give consistent rollout/rollback semantics across environments with `--atomic` and `helm diff` preview.

**How to apply:** When discussing K8s deployment, rollback, or secret rotation, reference these secret names and chart paths. Always suggest `--atomic --timeout 5m` for production upgrades.
