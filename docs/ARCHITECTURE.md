# AuthN/AuthZ Sidecar - Architektur & Implementierungsplan

## Übersicht

Der **k8s-auth-sidecar** (Request Router Sidecar) ist ein Quarkus-basierter Microservice, der als Sidecar in einem Kubernetes-Pod läuft und Authentifizierung (AuthN) sowie Autorisierung (AuthZ) für den Haupt-Container übernimmt.

## Architekturdiagramm (ASCII)

![System Architecture](images/architecture.png)

## Request Flow

![Request Flow](images/request_flow.png)

## Module & Komponenten (Maven Multi-Module)

Die gesamte HTTP-Pipeline ist reaktiv implementiert (`Mutiny Uni`), was höchste Parallelität bei minimalem Ressourcenverbrauch garantiert.

## Betriebsmodi

### 1. Gateway Mode (ext_authz) - EMPFOHLEN
In diesem Modus wird der Sidecar von einem externen Gateway (Envoy/Nginx) zur Autorisierung kontaktiert.
- **Endpoint**: `GET /authorize`.
- **Logic**: Der Sidecar wertet die Header `X-Forwarded-Uri` und `X-Forwarded-Method` aus.
- **Response**: `200 OK` delegiert die Anfrage an das eigentliche Ziel weiter. Rollen-Enrichment erfolgt über Antwort-Header (`X-Auth-User-Id`, `X-Enriched-Roles`).

### 2. Sidecar Proxy Mode (Legacy)
In diesem Modus ist der Sidecar der direkte Einstiegspunkt und leitet Traffic per Streaming weiter.
- **Entry**: `AuthProxyFilter` fängt alle Requests an `/**` ab.
- **Proxy**: `HttpProxyService` streamt den Request an `localhost:$PROXY_TARGET_PORT`.

## Architektur für lokale Entwicklung (Dev-Profil & Mocking)

Für eine erstklassige Developer Experience ohne externe Abhängigkeiten nutzt der Sidecar im Quarkus `%dev` Profil ein dediziertes Setup:
- **WireMock-Integration**: Zwei vorkonfigurierte WireMock-Instanzen (`docker-compose.dev.yml`) simulieren den Identity Provider (OIDC Discovery, JWKS, Token-Generierung) und den externen Roles-Service (dynamische Response-Templates).
- **In-Memory Alternative**: Für reine Code-Tests ohne Container kann ein `@IfBuildProfile("dev")`-getaggter Client (`InMemoryRolesService`) als Fallback einkompiliert werden.
- **Auto-Config**: Im `%dev`-Modus werden Caches für Roles und Policies deaktiviert, um sofortige Testrückmeldungen (Live-Reloading) zu ermöglichen.

## Technologie-Stack

| Komponente | Technologie |
|------------|-------------|
| Runtime | Quarkus 3.32.x (Native Image Support) |
| Language | Java 21 |
| OIDC | quarkus-oidc |
| HTTP Client | quarkus-rest-client-reactive |
| Policy Engine | OPA WASM (Chicory Embedded) |
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
- **118 automatisierte Tests** (POJO + Ext + Quarkus)
- **PIT Test Strength > 78%** (unser Gold-Standard für Qualität)
- **PIT Line Coverage > 52%** (modulabhängig, auth-core: 91%)

Weitere Details zum Testen findest du in unserem **legendären Testing-Abschnitt** im [README.md](../README.md#🧪-so-testest-du-das-projekt-–-schritt-für-schritt-super-einfach-erklärt).

---

## 🛡️ Sicherheitsaspekte

- **Zero-Trust**: Jede Anfrage wird strikt validiert. Vertrauen ist gut, Kontrolle ist besser!
- **Streaming Proxy**: Schützt vor Out-of-Memory-Attacken bei riesigen Uploads.
- **Secure Defaults**: Alles ist standardmäßig verboten (`Deny by default`).
- **WASM Hot-Reload**: Policies können im laufenden Betrieb ohne Neustart aktualisiert werden. Pool-Größe konfigurierbar via `OPA_POOL_SIZE` (Default: 50), gesichert durch `AtomicReference<ArrayBlockingQueue>`.
- **Fail-Open Strategy (Roles)**: Bei Ausfall oder Timeout des `RolesService` greift ein `@Fallback` – der `AuthContext` behält seine JWT-basierten Rollen. Abgesichert durch `@Timeout(200ms)` und `@CircuitBreaker`.

---

Dieses Dokument wird stetig erweitert. Bei Fragen wende dich an die Architektur-Gurus! 🚀

---

## ⚡ Performance & Production Readiness (Roadmap)

> [!NOTE]
> Das Projekt hat **POC-Reife** erreicht (alle Kernfeatures funktional, Demo in < 3 Minuten lauffähig). Für Production-Deployments mit **> 1000 RPS** wurden in Lasttests die folgenden Flaschenhälse identifiziert. Die Architektur ist bereits auf deren Beseitigung ausgelegt.

### Identifizierte Bottlenecks & geplante Lösungen

| # | Bottleneck | Ursache | Geplante Lösung |
|---|-----------|---------|-----------------|
| 1 | **Event-Loop CPU-Blockade** [FIXED] | JWT-Parsing & OPA-Evaluierung beanspruchen CPU. Synchrones Blocken verhindert hohe RPS. | Offloaded on the Quarkus Worker-Pool. **Achtung**: Virtual Threads (Loom) sind für Quarkus 3.15+ die Ziel-Architektur. |
| 2 | **WASM Pool Exhaustion** | Pro Request ist eine WASM-Instanz gelockt. Bei hoher Concurrency (Burst) leert sich der Pool. | Pool ist per `OPA_POOL_SIZE` konfigurierbar (Default: 50). Nutzt `AtomicReference<ArrayBlockingQueue<OpaPolicy>>` für thread-sichere Hot-Reload-Swaps. Monitoring via `sidecar_wasm_pool_active`. |
| 3 | **Synchrones JSON-Logging** | `quarkus-logging-json` schreibt Logs synchron auf dem Event-Loop. | Asynchrones Logging aktivieren: `quarkus.log.handler.console.async=true`. |
| 4 | **GC-Druck durch Header-Parsing** | Header-Propagierung im Proxy nutzt `Map`-Iterationen. | Umstellung auf Vert.x `MultiMap` in `HttpProxyService`. |

### POC vs. Production

| Dimension | POC (aktuell) | Production (Ziel) |
|-----------|--------------|-------------------|
| Durchsatz | ~1000 RPS | > 2000 RPS |
| Event-Loop-Safety | **Offloaded (Worker-Pool)** | Optimized / Virtual Threads |
| Connection Pool | Default 100 | Lastabhängig konfiguriert |
| Logging | Synchron | Asynchron |
| Header-Handling | Stream/Map | Vert.x MultiMap |

> [!IMPORTANT]
> Für den **POC** sind alle oben genannten Punkte bewusst zurückgestellt – Korrektheit und Stabilität der Auth-Pipeline haben Vorrang. Die Architektur ist modular genug, um diese Optimierungen schrittweise einzuführen, ohne die Domain-Logik anzufassen.
