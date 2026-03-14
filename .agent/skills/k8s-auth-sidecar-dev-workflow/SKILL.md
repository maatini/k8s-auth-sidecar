---
name: k8s-auth-sidecar-dev-workflow
description: Lokale Entwicklung, Testing und Debugging des k8s-auth-sidecar (Quarkus + WireMock + Devbox + Coverage + Mutation Testing)
---

**Wann diesen Skill verwenden?**  
- Immer bei lokaler Entwicklung (`quarkus:dev`)  
- Bei Änderungen an Filtern, Services, Policies oder Config  
- Beim Schreiben oder Debuggen von Tests (POJO + Integration)  
- Vor jedem Commit / PR  

**60-Sekunden-Dev-Setup (Copy & Paste)**
```bash
devbox shell
docker compose -f docker-compose.dev.yml up -d          # WireMock OIDC + Roles
mvn compile quarkus:dev                               # Sidecar starten
export TOKEN=$(curl -s -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/test
```

**Wichtige Befehle**
- `devbox run test` → alle Tests  
- `devbox run test:coverage` → JaCoCo-Report (`target/jacoco-report`)  
- `mvn pitest:mutationCoverage` → PIT (nur POJO + ExtTests)  
- `mvn compile` → WASM neu bauen (`.rego` → `.wasm`)  

**Test-Ergebnisse & Ziele (2026-03-13, gemessen)**
- auth-core: **47 Tests, 0 Failures** ✅  
- opa-wasm: **47 Tests, 0 Failures** ✅  
- config: **7 Tests, 0 Failures** ✅  
- ext-authz: **26 Tests, 0 Failures** ✅  
- Gesamt: **127 Tests, 0 Failures** ✅  
- PIT Test Strength auth-core: **82%** ✅ (Ziel: >80%)  
- PIT Line Coverage auth-core: **71%** (247/349 Zeilen)  

**Pitfalls**
- Immer zuerst WireMock starten!  
- In `%dev` ist `AUTH_ENABLED=false` – für echte Auth `AUTH_ENABLED=true` setzen  
- Coverage nur mit Docker (Testcontainers) vollständig  

**Verwandte Skills**  
- pit-mutation-testing-coverage  
- policy-testing-validation  
- quarkus-sidecar-ext-authz-pattern
