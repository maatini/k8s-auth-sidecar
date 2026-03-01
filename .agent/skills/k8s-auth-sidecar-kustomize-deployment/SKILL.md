---
name: k8s-auth-sidecar-kustomize-deployment
description: Erstelle Kustomize-Overlays, Pod-Specs, Service-Routing und ConfigMap für Policies beim Deployment des k8s-auth-sidecar.
---
**Wichtige Env-Variablen:**
- `PROXY_TARGET_PORT`
- `OIDC_AUTH_SERVER_URL`
- `ROLES_SERVICE_URL`
- `OPA_MODE=embedded|external`
