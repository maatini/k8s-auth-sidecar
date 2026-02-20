<div align="center">
  <img src="docs/images/banner.png" alt="K8s-Auth-Sidecar Banner" width="100%">
</div>

# K8s-Auth-Sidecar - AuthN/AuthZ Sidecar für Kubernetes

[![Quarkus](https://img.shields.io/badge/Quarkus-3.17-blue.svg)](https://quarkus.io)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

**K8s-Auth-Sidecar** (Request Router Sidecar) ist ein Quarkus-basierter Microservice, der als Sidecar in Kubernetes-Pods läuft und Authentifizierung (AuthN) sowie Autorisierung (AuthZ) für den Haupt-Container übernimmt – inklusive dynamischem Rollen-Enrichment und blitzschneller lokaler Entwicklung.

## ✨ Features

- ⚡ **Blitzschnelle lokale Entwicklung**: Out-of-the-Box Mocking für Identity Provider (Keycloak/Entra) und Roles Service via WireMock.
- 🏢 **Multi-Tenant OIDC-Support**: Standardisiert für Keycloak und Microsoft Entra ID (Azure AD).
- 🧠 **Flexible Policy-Engine**: Eingebettete OPA-Regeln (Hot-Reload) oder externer OPA-Server.
- ➕ **Rollen-Enrichment**: Nahtlose Integration mit externem Roles/Permissions-Service.
- ⚡ **Reaktive Pipeline**: Non-blocking AuthN → Enrichment → AuthZ Verarbeitung.
- 🛡️ **Zero-Trust**: Jede Anfrage wird validiert.
- 🎯 **Zentrales Path-Matching**: Ant-Style Patterns (`/**`, `/*`) über praktisches `PathMatcher`-Utility.
- 📊 **Observability**: Prometheus Metrics, JSON Logging und OpenTelemetry out-of-the-box.
- 🔒 **Sicherer Lifecycle**: Ordnungsgemäße Ressourcen-Freigabe (`@PreDestroy`) aller Clients.
- 🚀 **Native Image**: Voller Support für ressourcenschonende GraalVM Native Images.
- 🚢 **Kubernetes-Ready**: Kustomize-basierte Deployment-Manifeste.

## 🚀 Lokale Entwicklung – In 60 Sekunden

Beschleunige deine lokale Entwicklung mit unserer vorkonfigurierten, magischen Dev-Umgebung. Alles, was du brauchst, ist out-of-the-box eingerichtet – dank dem dynamischen Quarkus `%dev` Profil und WireMock.

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
   Startet lokal einen OIDC-Server (Port 8090) und einen Mock für den Roles Service (Port 8089).
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```

3. **Sidecar im Dev-Modus starten**
   ```bash
   mvn compile quarkus:dev
   ```
   **Was passiert im Hintergrund?** Das `%dev`-Profil wird automatisch aktiviert und konfiguriert den Sidecar optimal für die lokale Entwicklung:
   - 🔌 **HTTP-Port**: Wechselt auf `8080`.
   - 🔑 **OIDC-Issuer**: Zeigt direkt auf den lokalen WireMock OIDC-Server (`http://localhost:8090/realms/master`).
   - 🧑‍💻 **Roles Service**: Verbindet sich automatisch mit dem lokalen WireMock Roles-Server (`http://localhost:8089`).
   - 🚀 **Caching**: Ist für Roles & Policies im Dev-Modus deaktiviert, damit du Änderungen sofort testen kannst.

4. **Einen Test-JWT abrufen**
   WireMock ist so vorkonfiguriert, dass er dir auf Knopfdruck ein gültiges, signiertes JWT für einen Test-User (`test-user-123`) ausstellt:
   ```bash
   export TOKEN=$(curl -s -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token)
   ```
   *Test-Anfrage an den Sidecar:*
   ```bash
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/something
   ```

### 🛠️ Mocking anpassen (Mocks / Rollen ändern)

Du möchtest testen, wie sich der Sidecar bei einem anderen User oder mit anderen Rollen verhält?
- **OIDC/JWT-Token**: Die Mappings liegen unter `wiremock/oidc/mappings/`.
- **Rollen & Permissions**: Die Mappings liegen unter `wiremock/roles/mappings/`.

*(WireMock nutzt Request-Templating: Das Mapping `roles.json` liest z.B. automatisch die `userId` aus der aufgerufenen URL aus und fügt sie in die Response ein.)*

### 💡 Alternative ohne Docker: In-Memory Roles Service
Falls du Docker Compose lokal nicht nutzen möchtest, kannst du einen leichtgewichtigen Mock direkt im Sidecar-Code nutzen.
Erstelle dazu einfach folgende Klasse, die nur im `%dev`-Profil aktiv ist:

```java
package space.maatini.sidecar.client;

import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import space.maatini.sidecar.model.RolesResponse;

import java.util.Set;

@Alternative
@Priority(1)
@ApplicationScoped
@RestClient
@IfBuildProfile("dev")
public class InMemoryRolesService implements RolesServiceClient {
    @Override
    public Uni<RolesResponse> getRoles(String userId) {
        return Uni.createFrom().item(
            new RolesResponse(userId, Set.of("developer"), Set.of("read:all"), "tenant-A")
        );
    }
    // Implementiere die anderen Interface-Methoden identisch...
}
```

---

## 🏗️ Architektur

![System Architecture](docs/images/architecture.png)

## 🧠 Funktionsweise im Detail

### 1. Wer liefert welche Daten?

| Komponente | Verantwortung | Beispiel-Daten |
|------------|---------------|----------------|
| **Client** | Authentifizierung | JWT Token (`Authorization: Bearer ...`) |
| **Identity Provider**<br>(Keycloak / Entra ID) | **Identität & grobe Rollen**<br>Bestätigt, wer der User ist. | `sub: "user-123"`<br>`email: "max@space.maatini"`<br>`roles: ["user"]` |
| **Roles Service**<br>(Externer Microservice) | **Feingranulare Rechte**<br>Enrichment: Ergänzt applikationsspezifische Berechtigungen dynamisch. | `roles: ["project-admin"]`<br>`permissions: ["delete:reports"]`<br>`tenant: "project-x"` |
| **OPA Policy**<br>(Rego Datei) | **Das Regelwerk**<br>Definiert die finale Logik, wer was darf. | *"Wenn User Rolle 'admin' hat und Pfad '/admin' ist -> ERLAUBEN"* |

### 2. Der Entscheidungs-Ablauf

![Request Flow](docs/images/request_flow.png)

Jeder Request durchläuft diese Pipeline:
1. **🔍 Token Validierung**: Ist das JWT gültig? (Signatur via JWKS, Ablaufdatum, Issuer). *(Fehler: `401 Unauthorized`)*
2. **➕ Enrichment**: Der Sidecar fragt den **Roles Service**: *"Was darf dieser User noch?"*. (Die Antwort wird für kurze Zeit gecacht).
3. **⚖️ Policy Check**: Der Sidecar baut ein Input-JSON (User + Request + erweiterte Rollen) und fragt die **OPA-Engine**.
   - Input: `{"user": {"roles": ["user", "admin"]}, "request": {"method": "DELETE"}}`
   - Policy: `allow { input.user.roles[_] == "admin" }`
4. **🚀 Proxy**:
   - **Erlaubt:** Request wird an deine Applikation (z.B. Port 8081) weitergeleitet. Auth-Infos werden als Header (`X-Auth-User-Roles`) angehängt.
   - **Verboten:** User erhält sofort `403 Forbidden`. Deine App wird gar nicht erst belästigt.

### 3. Wie werden Regeln aktualisiert?
Du musst den Sidecar **nicht neu starten**, um OPA-Regeln zu ändern (bei `OPA_MODE=embedded`):
1. Regeln liegen z.B. in einer Kubernetes **ConfigMap**.
2. Kubernetes aktualisiert die Datei im Pod bei Änderungen.
3. Der Sidecar lädt die neuen Regeln **automatisch (Hot Reload)** in wenigen Sekunden.

## ⚙️ Konfiguration

### Umgebungsvariablen

| Variable | Beschreibung | Standard |
|----------|--------------|----------|
| `OIDC_AUTH_SERVER_URL` | Keycloak Auth-Server URL | `https://keycloak.example.com/realms/myrealm` |
| `OIDC_CLIENT_ID` | OIDC Client ID | `k8s-auth-sidecar` |
| `OIDC_CLIENT_SECRET` | OIDC Client Secret | - |
| `OIDC_TENANT_ENABLED` | Multi-Tenant aktivieren | `false` |
| `ENTRA_AUTH_SERVER_URL` | Entra ID Auth-Server URL | - |
| `ENTRA_CLIENT_ID` | Entra ID Client ID | - |
| `ROLES_SERVICE_URL` | URL des externen Roles-Microservice | `http://roles-service:8080` |
| `PROXY_TARGET_HOST` | Backend-Host (App Container) | `localhost` |
| `PROXY_TARGET_PORT` | Backend-Port (App Container) | `8081` |
| `OPA_ENABLED` | OPA-Policy-Evaluation aktivieren | `true` |
| `OPA_MODE` | `embedded` (intern) oder `external` | `embedded` |
| `OPA_URL` | Externer OPA-Server URL | `http://localhost:8181` |
| `QUARKUS_HTTP_CORS_ORIGINS` | Erlaubte CORS Origins | `*` (nur Dev!) |

*Siehe `src/main/resources/application.yaml` für alle Konfigurationsmöglichkeiten inkl. des neuen `%dev` Profils.*

## 📋 Policy-Konfiguration

Policies werden in Rego geschrieben und im `/policies`-Verzeichnis abgelegt.

### Beispiel-Policy

```rego
package authz

import future.keywords.if
import future.keywords.in

default allow := false

# Superadmin hat Zugriff auf alles
allow if {
    "superadmin" in input.user.roles
}

# Admin-Pfade nur für Admins
allow if {
    startswith(input.request.path, "/api/admin")
    "admin" in input.user.roles
}

# Lese-Zugriff für authentifizierte Benutzer
allow if {
    input.request.method == "GET"
    startswith(input.request.path, "/api/")
    role_match({"admin", "user", "viewer"})
}

# Helper-Funktion
role_match(required_roles) if {
    some role in input.user.roles
    role in required_roles
}
```

### Policy-Input-Struktur (JSON)
```json
{
  "request": {
    "method": "GET",
    "path": "/api/users",
    "headers": { "X-Request-ID": "..." }
  },
  "user": {
    "id": "user-123",
    "email": "user@example.com",
    "roles": ["user", "admin"],
    "permissions": ["read:users"],
    "tenant": "my-tenant"
  }
}
```

## 🚢 Kubernetes Deployment

### Mit Kustomize

```bash
kubectl apply -k k8s/overlays/development
kubectl apply -k k8s/overlays/production
```

### Sidecar zu bestehendem Deployment hinzufügen

```yaml
spec:
  containers:
    # 1. Deine bestehende Applikation
    - name: my-application
      image: my-app:latest
      ports:
        - containerPort: 8081  # Interner Port

    # 2. Den K8s-Auth-Sidecar hinzufügen
    - name: k8s-auth-sidecar
      image: space.maatini/k8s-auth-sidecar:1.0.0
      ports:
        - containerPort: 8080  # Externer Port (auf den der Service zeigt!)
      env:
        - name: PROXY_TARGET_PORT
          value: "8081"
        - name: OIDC_AUTH_SERVER_URL
          value: "https://keycloak.example.com/realms/myrealm"
      volumeMounts:
        - name: policies
          mountPath: /policies

  volumes:
    - name: policies
      configMap:
        name: my-app-policies
```

**Wichtig:** Dein Kubernetes `Service` muss auf Port `8080` (Sidecar) routen, nicht direkt auf die App!

## 📊 Monitoring

**Prometheus Metrics** (unter `/q/metrics`):
- `sidecar_auth_success_total` / `sidecar_auth_failure_total`
- `sidecar_authz_allow_total` / `sidecar_authz_deny_total`
- `sidecar_proxy_requests_total` / `sidecar_proxy_errors_total`

**Health Checks**:
- Liveness: `curl http://localhost:8080/q/health/live`
- Readiness: `curl http://localhost:8080/q/health/ready`

## 🔐 Sicherheit

- **Token-Validierung**: Signatur-Prüfung via JWKS, Expiration-Check, Issuer-Check.
- **Best Practices**:
  - ✅ Secrets über K8s Secrets einspeisen.
  - ✅ TLS für externe Verbindungen (Roles Service, IdP).
  - ✅ Audit-Logging am Sidecar aktivieren.

## 🚀 CI/CD & Releases

Das Projekt nutzt GitHub Actions für Continuous Integration und schnelles Deployment:
- **CI Pipeline (`ci.yml`)**: Führt bei jedem PR und Push auf `main` Tests aus, testet GraalVM Native Images, baut Multi-Arch Docker-Images (`linux/amd64`, `linux/arm64`), generiert CycloneDX SBOMs und scant das Image mit Trivy nach Schwachstellen.
- **Release Pipeline (`release.yml`)**: Wird automatisch beim Pushen von Tags (z.B. `v0.1.0`) getriggert. Baut Release-Images (JVM und Native), befüllt OCI-Labels dynamisch und pusht sie nach `ghcr.io/maatini/k8s-auth-sidecar`. Zudem wird ein automatisches GitHub Release mit Changelog erstellt.
- **Dependency Automation**: Dependabot & Renovate sorgen in Kombination für regelmäßige Security-Updates von Maven-Abhängigkeiten, Docker-Images und GitHub Actions (inkl. Auto-Merge für Minor/Patch-Updates).

## 🧪 Testing & Docker Build

### Unit & Integration Tests
```bash
mvn test
mvn verify

# Coverage-Report generieren (target/jacoco-report/index.html)
mvn test -Dquarkus.jacoco.report=true
```

### OPA Policy Tests (Lokal)
```bash
brew install opa
opa eval -i input.json -d src/main/resources/policies/ 'data.authz.allow'
```

### Docker und Native Image Build
```bash
# Standard JVM-Image
docker build -t space.maatini/k8s-auth-sidecar:1.0.0 .

# Leichtgewichtiges Native Image (GraalVM, dauert länger)
docker build -f Dockerfile.native -t space.maatini/k8s-auth-sidecar:1.0.0-native .
```

## 📁 Projektstruktur

```
k8s-auth-sidecar/
├── docs/                     # Architektur-Bilder & Docs
├── k8s/                      # Kubernetes Base & Overlays
├── src/main/java/space/maatini/sidecar/
│   ├── config/               # Quarkus Konfiguration
│   ├── client/               # REST-Clients (z.B. Roles Service)
│   ├── filter/               # HTTP-Filter & Pipeline
│   └── util/                 # Utilities (PathMatcher)
├── src/main/resources/       
│   ├── application.yaml      # Config inkl. %dev Profil
│   └── policies/             # OPA-Regeln (.rego)
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
