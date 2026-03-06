---
name: opa-authz-engineer-for-sidecar
description: Rego-Policies schreiben, testen und hot-reloaden (embedded WASM)
---

**Input-JSON (immer so!)**
```json
{
  "user": { "id": "...", "roles": ["admin"], "permissions": ["read"] },
  "request": { "method": "GET", "path": "/api/admin/123" }
}
```

**Wichtige Regeln**
- `default allow := false`  
- `allow if { "admin" in input.user.roles }`  
- Public-Paths: `/health`, `/api/public/**`, `/q/*`  

**Test-Befehle**
```bash
opa test src/main/resources/policies -v
mvn compile          # WASM neu bauen
```

**Hot-Reload**
- ConfigMap ändern → Kubernetes Volume-Event → Watcher erkennt → `recompileWasm` + `loadWasmModule`  

**Pitfalls**
- `startwith` statt `startswith` (Tippfehler!)  
- Immer `import future.keywords.if`  
- Sensitive-Pfade mit `permissions` prüfen  

**Verwandte Skills**  
- policy-testing-validation
