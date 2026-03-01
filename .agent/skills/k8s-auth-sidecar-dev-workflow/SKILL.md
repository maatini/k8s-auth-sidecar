---
name: k8s-auth-sidecar-dev-workflow
description: Entwickle, teste und debugge den k8s-auth-sidecar (Quarkus + WireMock + Docker-Compose). Verwende diesen Skill bei allen lokalen Dev-Aufgaben, Tests oder Mock-Setup.
---

# k8s-auth-sidecar Development Workflow

**Immer anwenden bei:**
- `quarkus:dev`, Docker-Compose-Dev, WireMock-Mocks, JWT-Generierung
- Änderungen an Config, Filters, Clients oder Policies

**Schritt-für-Schritt-Anweisung:**
1. Starte immer zuerst `docker compose -f docker-compose.dev.yml up -d`
2. Verwende `mvn compile quarkus:dev -Dquarkus.http.port=8080`
3. Generiere Test-Token mit dem bereitgestellten curl-Befehl gegen WireMock-Keycloak
4. Teste mit `curl -H "Authorization: Bearer $TOKEN" ...`
5. Bei Policy-Änderungen: `mvn compile` (kompiliert Rego → WASM automatisch)
6. Coverage-Ziel: ≥ 69 % (JaCoCo)
