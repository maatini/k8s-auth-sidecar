---
name: opa-authz-engineer-for-sidecar
description: Schreibe, teste und hot-reloade OPA/Rego-Policies für den k8s-auth-sidecar (embedded WASM + ConfigMap). Verwende bei allen AuthZ-Regeln, Input-JSON-Struktur oder Policy-Updates.
---
# OPA AuthZ Engineer für k8s-auth-sidecar

**Input-JSON immer:**
{
  "user": { "id": "...", "roles": ["..."] },
  "request": { "method": "GET|POST|...", "path": "/api/..." }
}

**Regeln:**
- Verwende `allow` / `deny` mit `package authz`
- Hot-Reload wird automatisch über Kubernetes Volume-Events erkannt
- Policies liegen in `/policies/*.rego` (im Container)
- Teste immer mit `opa test` und im embedded Modus
