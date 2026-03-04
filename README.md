<div align="center">
  <img src="docs/images/banner.png" alt="K8s-Auth-Sidecar Banner" width="100%">
</div>

# K8s-Auth-Sidecar - AuthN/AuthZ Sidecar für Kubernetes

[![Quarkus](https://img.shields.io/badge/Quarkus-3.17-blue.svg)](https://quarkus.io)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

**K8s-Auth-Sidecar** (Request Router Sidecar) ist ein Quarkus-basierter Microservice, der als Sidecar in Kubernetes-Pods läuft und Authentifizierung (AuthN) sowie Autorisierung (AuthZ) für den Haupt-Container übernimmt – inklusive dynamischem Rollen-Enrichment und blitzschneller lokaler Entwicklung.

## ✨ Features

- ⚡ **Blitzschnelle lokale Entwicklung**: Out-of-the-Box Mocking für Identity Provider (Keycloak) via WireMock.
- 🏢 **OIDC-Support**: Standardisierter Support für Keycloak und generische OIDC-Provider.
- 🧠 **Embedded Policy-Engine**: In-Memory OPA-WASM-Engine (Chicory) mit Hot-Reload.
- ⚡ **Reaktive Pipeline**: Vollständig non-blocking AuthN → AuthZ Verarbeitung mit Mutiny `Uni`.
- 🛡️ **Zero-Trust**: Jede Anfrage wird validiert.
- 📡 **Streaming Proxy**: Request-Bodies werden **nie** vollständig in den RAM geladen – echtes Vert.x Streaming für beliebig große Payloads.
- 🎯 **Zentrales Path-Matching**: Ant-Style Patterns (`/**`, `/*`) über praktisches `PathMatcher`-Utility.
- ⚡ **High-Performance Caching**: Effiziente Caffeine-Caches für JWT-Validierungen und Policies.
- 📊 **Observability**: Prometheus Metrics, JSON Logging und Health Checks out-of-the-box.
- 🚀 **Native Image**: Voller Support für ressourcenschonende GraalVM Native Images.
- 🚢 **Kubernetes-Ready**: Kustomize-basierte Deployment-Manifeste.

## 🚀 Lokale Entwicklung – In 60 Sekunden

Beschleunige deine lokale Entwicklung mit unserer vorkonfigurierten Dev-Umgebung via Quarkus `%dev` Profil und WireMock.

### Voraussetzungen
- Docker & Docker Compose
- JDK 21+ & Maven

### Schritt-für-Schritt

1. **Projekt klonen**
   ```bash
   git clone https://github.com/maatini/k8s-auth-sidecar.git
   cd k8s-auth-sidecar
   ```

2. **Mock-Infrastruktur starten (WireMock)**
   Startet lokal einen OIDC-Server (Port 8090).
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```

3. **Sidecar im Dev-Modus starten**
   ```bash
   mvn compile quarkus:dev
   ```

4. **Einen Test-JWT abrufen**
   ```bash
   export TOKEN=$(curl -s -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token)
   ```
   *Test-Anfrage an den Sidecar:*
   ```bash
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/something
   ```

---

## 🏗️ Architektur

Der Sidecar fungiert als intelligenter Reverse-Proxy vor deinem Anwendungs-Container. Er fängt alle eingehenden Anfragen ab, validiert die Identität des Aufrufers gegen einen OIDC-Provider und prüft die Berechtigungen gegen lokal geladene OPA-WASM-Policies.

## 🧠 Funktionsweise im Detail

Jeder Request durchläuft diese Pipeline:
1. **🔍 Token Validierung**: Ist das JWT gültig? (Signatur, Ablaufdatum, Issuer). *(Fehler: `401 Unauthorized`)*
2. **⚖️ Policy Check**: Der Sidecar fragt die **OPA-Engine** (WASM).
   - Input: `{"user": {"id": "...", "roles": [...]}, "request": {"method": "GET", "path": "..."}}`
3. **🚀 Proxy**:
   - **Erlaubt:** Request wird an das Backend (Port 8081) weitergeleitet.
   - **Verboten:** User erhält `403 Forbidden`.

### Hot Reload
OPA-Regeln (`.rego` Dateien) im `/policies`-Verzeichnis werden automatisch überwacht. Bei Änderungen wird das WASM-Modul im Hintergrund rekompiliert und neu geladen, ohne den Sidecar neu zu starten.

## ⚙️ Konfiguration

### Umgebungsvariablen

| Variable | Beschreibung | Standard |
|----------|--------------|----------|
| `OIDC_AUTH_SERVER_URL` | OIDC Auth-Server URL (z.B. Keycloak Realm) | `http://localhost:8090/realms/master` |
| `OIDC_CLIENT_ID` | OIDC Client ID | `k8s-auth-sidecar` |
| `PROXY_TARGET_HOST` | Backend-Host (App Container) | `localhost` |
| `PROXY_TARGET_PORT` | Backend-Port (App Container) | `8081` |
| `AUTH_ENABLED` | Authentifizierung aktivieren | `true` |
| `AUTHZ_ENABLED` | OPA-Policy-Evaluation aktivieren | `true` |
| `OPA_WASM_PATH` | Pfad zur OPA-WASM-Datei | `classpath:policies/authz.wasm` |
| `AUDIT_ENABLED` | Audit-Logging aktivieren | `true` |
| `SIDECAR_LOG_LEVEL` | Log-Level (DEBUG, INFO, etc.) | `INFO` |

## 📋 Policy-Konfiguration

Policies werden in Rego geschrieben und im `/policies`-Verzeichnis abgelegt.

### Beispiel-Policy (`authz.rego`)

```rego
package authz

default allow := false

# Admin-Pfade nur für Admins
allow {
    startswith(input.request.path, "/api/admin")
    input.user.roles[_] == "admin"
}

# Lese-Zugriff für alle authentifizierten Benutzer
allow {
    input.request.method == "GET"
    startswith(input.request.path, "/api/")
}
```

## 🚢 Kubernetes Deployment

### Sidecar zu bestehendem Deployment hinzufügen

```yaml
spec:
  containers:
    - name: my-application
      image: my-app:latest
      ports:
        - containerPort: 8081

    - name: k8s-auth-sidecar
      image: ghcr.io/maatini/k8s-auth-sidecar:0.3.0
      env:
        - name: PROXY_TARGET_PORT
          value: "8081"
        - name: OIDC_AUTH_SERVER_URL
          value: "https://keycloak.example.com/realms/myrealm"
```

## 📊 Monitoring

**Metrics** (unter `/q/metrics`):
- `sidecar_auth_success_total` / `sidecar_auth_failure_total`
- `sidecar_authz_allow_total` / `sidecar_authz_deny_total`

**Health Checks**:
- Liveness: `/q/health/live`
- Readiness: `/q/health/ready`

## 🔐 Sicherheit & Performance

- **Token-Validierung & High-Speed Cache**: Signatur-Prüfung via JWKS, Expiration-Check, Issuer-Check – mit vorgeschaltetem, hochperformantem Caffeine-Cache, um redundante Kryptographieoperationen für bekannte Sessions zu umgehen.
- **Streaming Proxy**: Request-Bodies werden per Vert.x `sendStream()` weitergeleitet – kein `readAllBytes()` im gesamten Projekt. Auch 500 MB+ Payloads verursachen keinen Memory-Spike.
- **Reaktive Filter-Pipeline**: Der `AuthProxyFilter` ist vollständig non-blocking (`@ServerRequestFilter` + `Uni<Response>`), was den Durchsatz unter Last drastisch erhöht. Fallback-Error-Mechanismen allokieren Speicher in `O(1)`.
- **Rate-Limiting mit IP-Spoofing-Schutz**: `X-Forwarded-For`/`X-Real-IP` werden nur von konfigurierten `trusted-proxies` akzeptiert. Buckets nutzen einen größen- und zeitlimitierten Caffeine-Cache statt einer unbegrenzten `ConcurrentHashMap`.
- **CVE-gehärtete Container**: Alpine-Pakete werden via `apk upgrade` gepatcht, OPA-CLI ist auf v1.x aktualisiert (Go stdlib CVEs behoben).
- **Best Practices**:
  - ✅ Secrets über K8s Secrets einspeisen.
  - ✅ TLS für externe Verbindungen (Roles Service, IdP).
  - ✅ Audit-Logging am Sidecar aktivieren.
  - ✅ Trivy-Scan in CI-Pipeline für automatische Schwachstellen-Checks.

## 🚀 CI/CD & Releases

Das Projekt nutzt GitHub Actions für Continuous Integration und schnelles Deployment nach den höchsten Security-Standards:
- **CI Pipeline (`ci.yml`)**: Führt bei jedem PR und Push auf `main` Tests aus, generiert SBOMs, und testet die Kompilation im Dry-Run.
- **Release Pipeline (`release.yml`)**: Wird automatisch beim Pushen von Tags (z.B. `v0.1.0`) getriggert. Baut Release-Images (JVM und Native), befüllt OCI-Labels dynamisch und fügt **Signed Provenance & SBOMs (SLSA Level 3)** hinzu.
- **Intelligente Caching-Mechanismen**: Die Docker Native-Pipelines erkennen bereits in der CI übersetzte Binaries und überspringen redundante GraalVM-Kompilationen, was Deployments auf bis zu 10x Beschleunigung bringt.
- **Dependency Automation**: Dependabot & Renovate sorgen in Kombination für regelmäßige Security-Updates von Maven-Abhängigkeiten, Docker-Images und GitHub Actions (inkl. Auto-Merge für Minor/Patch-Updates).
- **Docker-Sicherheit**: Alle Images nutzen standardmäßig nicht-privilegierte User (`sidecar` bzw. UID `1001`), um **niemals als Root** zu laufen, und integrieren automatisch standardisierte OCI-Labels zur vollen Traceability.

## 🧪 Testing & Docker Build

### Unit & Integration Tests

Das Projekt verfügt über eine umfassende Test-Suite (**über 90 automatisierte Tests**), die alle wesentlichen Aspekte der Anwendung abdeckt:

- **Unit-Tests**: Prüfen einzelne Klassen, rekordinhabende Utility-Methoden (`RequestUtils`, `IssuerUtils`, `PathMatcher`) und Service-Logik (`AuthenticationService`, `WasmPolicyEngine`).
- **Integrationstests (`@QuarkusTest`)**: Testen das Zusammenspiel der Komponenten, u.a. die JWT-Validierung (OIDC), externe Calls an den Roles Service und die Request-Filter-Pipeline.
- **Mocking (WireMock)**: 
  - `OidcWiremockTestResource` simuliert einen OIDC-Identity-Provider inkl. dynamischer JWT-Generierung.
  - `RolesServiceWiremockTestResource` mockt detailliert diverse Rollen- und Berechtigungs-Szenarien.
- **Testcontainers**: Werden für komplexe Integrationstests eingesetzt, z.B. um einen echten, externen OPA-Server hochzufahren und anzubinden.

**Aktuelle Testabdeckung (Code Coverage via JaCoCo):**
> [!NOTE]
> Die volle Testabdeckung wird nur erreicht, wenn Docker für die Integrationstests (Testcontainers) verfügbar ist.

| Klasse | Line Coverage | Branch Coverage | Mutation Score |
|---|---|---|---|
| **AuthenticationService** | 90.2% | **100.0%** | **53.0%** |
| **AuditLogFilter** | 100.0% | **100.0%** | 44.0% |
| **ProxyService** | 100.0% | 87.1% | 45.0% |
| **PolicyService** | 100.0% | 86.4% | **91.0%** |
| **AuthProxyFilter** | 90.6% | 65.4% | 44.0%* |
| **WasmPolicyEngine** | 71.9% | 55.8% | 19.0% |
| **PathMatcher** | 100.0% | 97.5% | **88.0%** |
| **GESAMT** | **89.8%** | **80.0%** | **57.0%** |

*\*Paket-Durchschnittswert*

- **Tests:** 143 (0 Failures, 0 Errors)

*Wichtige Kernkomponenten wie `AuthenticationService` und `AuditLogFilter` erreichen 100% Branch Coverage. Der Mutation Score von 57% reflektiert primär die Abdeckung durch reine Unit-Tests (POJOs), da PIT Integration-Tests derzeit überspringt.*

Du kannst die Tests und den Coverage-Report lokal wie folgt ausführen:

```bash
mvn test
mvn verify

# Coverage-Report generieren (target/jacoco-report/index.html)
mvn test -Dquarkus.jacoco.report=true
```

### OPA Policy Tests (Lokal - optional)
*(Hinweis: Der Sidecar benötigt die OPA-CLI zur Laufzeit nicht mehr, da er In-Memory WASM nutzt! Zum manuellen Testen von Policies ist die CLI jedoch hilfreich)*
```bash
brew install opa
opa eval -i input.json -d src/main/resources/policies/ 'data.authz.allow'
# Oder WASM kompilieren:
opa build -t wasm -e authz/allow -o src/main/resources/policies/authz.wasm src/main/resources/policies/
```

### Docker und Native Image Build
```bash
# Standard JVM-Image
docker build -t ghcr.io/maatini/k8s-auth-sidecar:0.3.0 .

# Leichtgewichtiges Native Image (GraalVM, dauert länger)
docker build -f Dockerfile.native -t ghcr.io/maatini/k8s-auth-sidecar:0.3.0-native .
```

## 📁 Projektstruktur

```
k8s-auth-sidecar/
├── docs/                     # Architektur-Bilder & Docs
├── k8s/                      # Kubernetes Base & Overlays
├── src/main/java/space/maatini/sidecar/
│   ├── config/               # Quarkus Konfiguration (SidecarConfig)
│   ├── client/               # REST-Clients (z.B. Roles Service)
│   ├── filter/               # HTTP-Filter & Pipeline
│   ├── service/              # PolicyService (OPA WASM), ProxyService
│   └── util/                 # Utilities (PathMatcher)
├── src/main/resources/       
│   ├── application.yaml      # Config inkl. %dev Profil
│   └── policies/             # OPA-Regeln (.rego → .wasm via Maven Build)
├── wiremock/                 # JSON Mock Mappings für Dev-Modus
├── docker-compose.dev.yml    # Wiremock Dev-Infrastruktur
├── Dockerfile                # JVM-Image
└── Dockerfile.native         # GraalVM-Image
```

## 🛠️ Erweiterung

### Eigene Claims verarbeiten

Den `AuthenticationService` erweitern:
```java
@ApplicationScoped
public class CustomAuthService extends AuthenticationService {
    @Override
    public AuthContext extractFromJwt(JsonWebToken jwt) {
        AuthContext base = super.extractFromJwt(jwt);
        return AuthContext.builder()
            .userId(base.userId())
            // ... eigene extraction logic
            .build();
    }
}
```

## 📄 Lizenz
Apache License 2.0 - siehe [LICENSE](LICENSE)

## 🤝 Contributing
Beiträge sind willkommen! Bitte lies zuerst die [CONTRIBUTING.md](CONTRIBUTING.md).

---

**Entwickelt von [space.maatini](https://github.com/maatini)**
