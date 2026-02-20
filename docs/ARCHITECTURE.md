# AuthN/AuthZ Sidecar - Architektur & Implementierungsplan

## Übersicht

Der **k8s-auth-sidecar** (Request Router Sidecar) ist ein Quarkus-basierter Microservice, der als Sidecar in einem Kubernetes-Pod läuft und Authentifizierung (AuthN) sowie Autorisierung (AuthZ) für den Haupt-Container übernimmt.

## Architekturdiagramm (ASCII)

![System Architecture](images/architecture.png)

## Request Flow

![Request Flow](images/request_flow.png)

## Komponenten

### 1. Request Interceptor (`RequestInterceptorFilter`)
- Fängt alle eingehenden HTTP-Anfragen ab
- Extrahiert den Bearer-Token aus dem `Authorization`-Header
- Leitet an den AuthN-Filter weiter

### 2. Authentication Filter (`AuthenticationFilter`)
- Validiert JWT-Tokens gegen konfigurierte Identity Provider
- Unterstützt Multi-Tenant OIDC:
  - **Keycloak**: `/.well-known/openid-configuration`
  - **Microsoft Entra ID**: `/.well-known/openid-configuration`
- Prüft:
  - Token-Signatur (via JWKS)
  - Token-Expiration
  - Audience-Claim
  - Issuer-Claim

### 3. Authorization Filter (`AuthorizationFilter`)
- Extrahiert User-ID aus Token-Claims
- Ruft Rollen/Rechte vom externen Microservice ab
- Evaluiert Policies mit eingebetteter OPA-Engine

### 4. Policy Engine (OPA Integration)
- Eingebettete OPA-Engine für Policy-Evaluation
- Rego-Policies für flexible Zugriffskontrolle
- Hot-Reload von Policies

### 5. Proxy Service (`ProxyService`)
- Leitet autorisierte Anfragen an den Main-Container weiter
- Fügt Security-Headers hinzu
- Propagiert relevante Claims

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
| Policy Engine | OPA (via REST oder embedded) |
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
OPA_POLICY_PATH=/policies
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

## Implementierungsplan

### Phase 1: Grundgerüst (Tag 1-2)
1. ✅ Quarkus-Projekt initialisieren
2. ✅ Basis-Projektstruktur erstellen
3. ✅ Request Interceptor implementieren
4. ✅ Proxy-Service für Request-Forwarding

### Phase 2: Authentifizierung (Tag 3-4)
1. ✅ OIDC-Integration für Keycloak
2. ✅ Multi-Tenant Support für Entra ID
3. ✅ JWT-Validierung und Claim-Extraktion
4. ✅ Token-Caching

### Phase 3: Autorisierung (Tag 5-6)
1. ✅ Roles-Service Client
2. ✅ OPA-Integration
3. ✅ Policy-Konfiguration
4. ✅ Decision-Caching

### Phase 4: Observability (Tag 7)
1. ✅ Prometheus Metrics
2. ✅ Structured JSON Logging
3. ✅ Health Checks
4. ✅ Tracing (optional)

### Phase 5: Deployment (Tag 8)
1. ✅ Dockerfile (Native Image)
2. ✅ Kubernetes Manifests
3. ✅ Helm Chart (optional)
4. ✅ Dokumentation

## Sicherheitsaspekte

- **Zero-Trust**: Jede Anfrage wird validiert
- **Token-Validierung**: Signatur, Expiration, Audience, Issuer
- **Secure Defaults**: Deny by default
- **TLS**: Kommunikation zwischen Services verschlüsselt
- **Rate Limiting**: Schutz vor Brute-Force
- **Audit Logging**: Alle Zugriffe werden protokolliert
