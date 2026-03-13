# AuthN/AuthZ Sidecar - Architektur & Implementierungsplan

## Übersicht

Der **k8s-auth-sidecar** (Request Router Sidecar) ist ein Quarkus-basierter Microservice, der als Sidecar in einem Kubernetes-Pod läuft und Authentifizierung (AuthN) sowie Autorisierung (AuthZ) für den Haupt-Container übernimmt.

## Architekturdiagramm (ASCII)

![System Architecture](images/architecture.png)

## Request Flow

![Request Flow](images/request_flow.png)

## Module & Komponenten (Maven Multi-Module)

### 1. Modul: `proxy`
- **Request Router (`ProxyResource`)**: Catch-all JAX-RS Resource.
- **Streaming Proxy (`HttpProxyService`)**: Leitet Anfragen **via non-blocking Streaming** an den Main-Container weiter.
- **Entry Filter (`AuthProxyFilter`)**: Erster Kontaktpunkt, delegiert an den Processor.

### 2. Modul: `auth-core`
- **Request Processor (`SidecarRequestProcessor`)**: Orchestriert AuthN, Enrichment und AuthZ.
- **Authentication Service (`AuthenticationService`)**: Validiert JWTs und extrahiert den `AuthContext`.
- **Authorization UseCase (`AuthorizationUseCase`)**: Evaluiert OPA-WASM Policies über `AuthorizationCommand`.
- **Roles Service (`RolesService`)**: Enriched den Context über den `RolesClient`.

### 3. Modul: `opa-wasm`
- **Policy Engine (`WasmPolicyEngine`)**: Eingebettete OPA-Engine (WASM) für In-Memory Evaluation.
- **Policy Service (`PolicyService`)**: Abstraktionsschicht für die Autorisierungs-Logik.
- **Rego Policies**: Lokale `.rego` Dateien und vorkompilierte `.wasm` Bundles.

### 4. Modul: `config`
- **Zentrale Konfiguration**: `SidecarConfig` (Quarkus Config).
- **Observability**: Health-Checks (`Liveness`, `Readiness`) und Micrometer Metriken.

Die gesamte HTTP-Pipeline ist vollständig reaktiv implementiert (`Mutiny Uni`), was höchste Parallelität bei minimalem Ressourcenverbrauch garantiert.

## Architektur für lokale Entwicklung (Dev-Profil & Mocking)

Für eine erstklassige Developer Experience ohne externe Abhängigkeiten nutzt der Sidecar im Quarkus `%dev` Profil ein dediziertes Setup:
- **WireMock-Integration**: Zwei vorkonfigurierte WireMock-Instanzen (`docker-compose.dev.yml`) simulieren den Identity Provider (OIDC Discovery, JWKS, Token-Generierung) und den externen Roles-Service (dynamische Response-Templates).
- **In-Memory Alternative**: Für reine Code-Tests ohne Container kann ein `@IfBuildProfile("dev")`-getaggter Client (`InMemoryRolesService`) als Fallback einkompiliert werden.
- **Auto-Config**: Im `%dev`-Modus werden Caches für Roles und Policies deaktiviert, um sofortige Testrückmeldungen (Live-Reloading) zu ermöglichen.

## Technologie-Stack

| Komponente | Technologie |
|------------|-------------|
| Runtime | Quarkus 3.x (Native Image Support) |
| Language | Java 21 |
| OIDC | quarkus-oidc |
| HTTP Client | quarkus-rest-client-reactive |
| Policy Engine | OPA WASM (Chicory) oder REST (external) |
| Metrics | Micrometer + Prometheus |
| Logging | quarkus-logging-json |
| Container | GraalVM Native Image |
| Orchestration | Kubernetes Sidecar Pattern |

## Konfiguration

### Umgebungsvariablen

```bash
# Identity Provider
OIDC_AUTH_SERVER_URL=https://keycloak.example.com/realms/myrealm
OIDC_CLIENT_ID=my-client
OIDC_TENANT_ENABLED=true

# Microsoft Entra ID (optional, multi-tenant)
ENTRA_AUTH_SERVER_URL=https://login.microsoftonline.com/{tenant}/v2.0
ENTRA_CLIENT_ID=azure-client-id

# Roles Microservice
ROLES_SERVICE_URL=http://roles-service:8080
ROLES_SERVICE_PATH=/api/v1/users/{userId}/roles

# Proxy Target
PROXY_TARGET_HOST=localhost
PROXY_TARGET_PORT=8081

# OPA
OPA_MODE=embedded
OPA_WASM_PATH=/policies/authz.wasm
OPA_DECISION_ENDPOINT=/v1/data/authz/allow

# Metrics & Logging
QUARKUS_LOG_LEVEL=INFO
QUARKUS_METRICS_ENABLED=true
```

## Policy-Beispiel (Rego)

```rego
package authz

default allow = false

# Erlaubt Zugriff wenn User die benötigte Rolle hat
allow {
    required_role := role_mapping[input.request.path][input.request.method]
    required_role == input.user.roles[_]
}

# Mapping von Pfaden zu benötigten Rollen
role_mapping := {
    "/api/admin": {"GET": "admin", "POST": "admin"},
    "/api/users": {"GET": "user", "POST": "admin"},
    "/api/public": {"GET": "public"}
}

# Erlaubt Zugriff für superadmin
allow {
    input.user.roles[_] == "superadmin"
}
```

## Deployment

### Sidecar-Pattern in Kubernetes

```yaml
spec:
  containers:
    # Main Application Container
    - name: application
      image: my-app:latest
      ports:
        - containerPort: 8081
    
    # Sidecar Container (AuthN/AuthZ)
    - name: k8s-auth-sidecar
      image: space.maatini/k8s-auth-sidecar:latest
      ports:
        - containerPort: 8080
      env:
        - name: PROXY_TARGET_HOST
          value: "localhost"
        - name: PROXY_TARGET_PORT
          value: "8081"
```

## 🧪 Implementierungsplan & Testing-Strategie

> [!IMPORTANT]
> **Qualität zuerst:** Für uns ist Testing kein lästiges Extra, sondern der Kern unserer Stabilität. Wir nutzen modernste Java-Techniken wie **PIT Mutation Testing**, um sicherzustellen, dass unsere Tests wirklich jeden Fehler finden.

### Unsere Metriken (Stand März 2026)
- **142 automatisierte Tests** (JVM + Native)
- **PIT Test Strength > 70%** (unser Gold-Standard für Qualität)
- **PIT Line Coverage > 75%**

Weitere Details zum Testen findest du in unserem **legendären Testing-Abschnitt** im [README.md](../README.md#🧪-so-testest-du-das-projekt-–-schritt-für-schritt-super-einfach-erklärt).

---

## 🛡️ Sicherheitsaspekte

- **Zero-Trust**: Jede Anfrage wird strikt validiert. Vertrauen ist gut, Kontrolle ist besser!
- **Streaming Proxy**: Schützt vor Out-of-Memory-Attacken bei riesigen Uploads.
- **Secure Defaults**: Alles ist standardmäßig verboten (`Deny by default`).
- **WASM Hot-Reload**: Policies können im laufenden Betrieb ohne Neustart aktualisiert werden.

---

Dieses Dokument wird stetig erweitert. Bei Fragen wende dich an die Architektur-Gurus! 🚀
