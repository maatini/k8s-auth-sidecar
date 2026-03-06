---
name: policy-testing-validation
description: OPA/Rego-Policies unit-testen und validieren (opa test + WASM)
---

**Wann verwenden?**  
- Bei jeder Änderung an `.rego`-Dateien  
- Vor dem Commit von Policies  
- Beim Debuggen von AuthZ-Fehlern (403)  
- Für Hot-Reload-Tests in Kubernetes  

**Wichtige Befehle**
```bash
# 1. Unit-Tests der Policies (empfohlen)
opa test src/main/resources/policies -v

# 2. Manuelle Evaluierung mit Input
opa eval -i input.json -d src/main/resources/policies/ 'data.authz.allow'

# 3. WASM-Kompilierung prüfen (Maven-Build)
mvn compile
```

**Input-Beispiel** (`input.json`)
```json
{
  "user": { "id": "u123", "roles": ["admin"], "permissions": ["read:all"] },
  "request": { "method": "GET", "path": "/api/admin/dashboard" }
}
```

**Hot-Reload-Test (Kubernetes)**
1. ConfigMap `k8s-auth-sidecar-policies` patchen
2. Logs des Sidecar-Containers prüfen:
   ```bash
   kubectl logs -c k8s-auth-sidecar -f | grep -i "recompile\|reload\|wasm"
   ```

**Pitfalls**
- Immer `import future.keywords.if` und `in` verwenden
- `opa test` muss **vor** dem Maven-Build laufen
- In `%dev` Profil wird WASM automatisch neu kompiliert
- Bei Fehlern: `opa build -t wasm ...` manuell testen

**Verwandte Skills**  
- opa-authz-engineer-for-sidecar  
- k8s-auth-sidecar-dev-workflow
