<div align="center">
  <img src="docs/images/banner.png" alt="K8s-Auth-Sidecar Banner" width="100%">

  # K8s-Auth-Sidecar - AuthN/AuthZ Sidecar für Kubernetes

  [![Quarkus](https://img.shields.io/badge/Quarkus-3.32.3-blue.svg?logo=quarkus)](https://quarkus.io)
  [![Java](https://img.shields.io/badge/Java-21-orange.svg?logo=openjdk)](https://openjdk.org)
  [![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
  [![Tests](https://img.shields.io/badge/Tests-56%20%E2%9C%85%200%20Failures-brightgreen.svg)](#-so-testest-du-das-projekt--schritt-f%C3%BCr-schritt-super-einfach-erkl%C3%A4rt)
  [![PIT Strength](https://img.shields.io/badge/PIT%20Strength-79%25-brightgreen.svg)](#7-mutation-testing-pit--qualit%C3%A4ts-check)
  [![Docker](https://img.shields.io/badge/Docker-Ready-blue.svg?logo=docker)](https://www.docker.com/)
  [![Kubernetes](https://img.shields.io/badge/Kubernetes-Ready-blue.svg?logo=kubernetes)](https://kubernetes.io/)
</div>

**K8s-Auth-Sidecar** (Request Router Sidecar) ist ein Quarkus-basierter Microservice, der als Sidecar in Kubernetes-Pods läuft und Authentifizierung (AuthN) sowie Autorisierung (AuthZ) für den Haupt-Container übernimmt – inklusive dynamischem Rollen-Enrichment und schneller lokaler Entwicklung.

> [!NOTE]
> Dieses Projekt ist für Cloud-Native Umgebungen optimiert, unterstützt **Native Image** via GraalVM und bietet **Hot-Reload** für Sicherheits-Policies.

---

> [!TIP]
> ## 🚀 POC‑Ready
> Das Projekt bildet alle Kernfeatures vollständig und stabil ab: **OIDC-Validierung (Keycloak & Entra ID), embedded OPA-WASM-Autorisierung und Envoy `ext_authz` Support**. Eine sofort lauffähige Demo startet in unter 3 Minuten:
> ```bash
> docker compose -f docker-compose.demo.yml up -d   # Demo-Stack (WireMock + Sidecar)
> ```
> Ideal für Stakeholder-Demos, technische Evaluierungen und Proof-of-Concept-Reviews.

---

## ✨ Features

- ⚡ **Schnelle lokale Entwicklung**: Out-of-the-Box Mocking für Identity Provider (Keycloak) via WireMock.
- 🏢 **OIDC-Support**: Standardisierter Support für Keycloak, Microsoft Entra ID und generische OIDC-Provider.
- 🧠 **Embedded Policy-Engine**: In-Memory OPA-WASM-Engine (Chicory) mit Hot-Reload der `.wasm` Regeln.
- ⚡ **Reaktive Pipeline**: Non-blocking AuthN → AuthZ Verarbeitung mit Mutiny `Uni`.
- 🛡️ **Zero-Trust**: Jede Anfrage wird zwingend validiert.
- 🛡️ **Envoy / Ingress Mode (`ext_authz`)**: Entwickelt für den Einsatz mit Envoy (Istio) oder Nginx. Stellt den `/authorize` Endpunkt als externer Autorisierungs-Service (PDP) bereit.
- 🚀 **Context Enrichment**: Reichert Anfragen an das Backend mit `X-Auth-User-Id` und `X-Enriched-Roles` an.
- 👤 **UserInfo Endpoint**: Bietet unter `/userinfo` strukturierte JSON-Antworten über Identität, Rollen und extrahierte Berechtigungen des Nutzers.
- 🎯 **Zentrales Path-Matching**: Ant-Style Patterns (`/**`, `/*`) über praktisches `PathMatcher`-Utility.
- 🚀 **Native Image Support**: Minimale Startup-Zeit (< 100ms) und extrem geringer Memory-Footprint.
- 📊 **Observability**: Prometheus Metrics, JSON Logging und Health Checks out-of-the-box.

---

## ⚡ Quick-Start (Local Dev)

Beschleunige deine lokale Entwicklung in Sekunden:

```bash
# 1. Infrastruktur starten (WireMock auf Port 8090)
docker compose -f docker-compose.dev.yml up -d

# 2. Sidecar im Dev-Modus starten (mit Hot-Reload)
mvn quarkus:dev

# 3. Test-Token abrufen & Request senden
export TOKEN=$(curl -s -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token)

# API Request simulieren
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/something

# ODER: Eigene Identität, Rollen und Permissions abfragen
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/userinfo
```

Für eine detaillierte Einführung siehe den [JUNIOR_GUIDE.md](JUNIOR_GUIDE.md).

---

## 🏗️ Architektur & Funktionsweise

Der Sidecar wird ausschließlich als **externer Autorisierungs-Service (PDP)** betrieben. Ingress-Controller (Envoy, Nginx) fragen den Sidecar über den `/authorize` Endpunkt: „Darf dieser User das?“. Der Sidecar antwortet mit `200 OK` (Erlaubt, inkl. Enrichment-Header) oder `403 Forbidden`.

### Request Flow (ext_authz Mode)

```mermaid
graph TD
    Client((Client)) -->|1. Request| Envoy[Envoy / Ingress]
    Envoy -->|2. Check /authorize| Sidecar[K8s-Auth-Sidecar]
    Sidecar -->|3. Validierung| OIDC[(OIDC Provider)]
    Sidecar -->|4. Enrichment| RolesService[Roles Service]
    Sidecar -->|5. Evaluierung| OPA{In-Memory<br/>OPA-WASM}
    OPA -.->|Allow + Context| Sidecar
    Sidecar -.->|6. 200 OK + Header| Envoy
    Envoy -->|7. Forward with Context| Backend[App Container]
    
    style Sidecar fill:#1792E5,stroke:#fff,stroke-width:2px,color:#fff
    style Backend fill:#1E2B3C,stroke:#000,stroke-width:2px,color:#fff
```

### 🧠 Die Pipeline im Detail
Jeder Request an `/authorize` durchläuft diese reaktiven Schritte:
1.  **🔍 Token Extraktion & Authentifizierung**: Der Route-Handler extrahiert den Bearer-Token, validiert Signatur/Issuer via Caffeine-Cache und erzeugt einen sicheren `AuthContext`. Schlägt dies fehl, wird sofort `HTTP 401` zurückgegeben.
2.  **🔄 Rollen-Enrichment**: Der `RolesService` ruft asynchron zusätzliche Rollen für den Nutzer ab und fügt sie dem `AuthContext` hinzu. Bei Fehlern (Timeout etc.) greift ein Fallback auf die im JWT enthaltenen Rollen.
3.  **⚖️ Policy Check & Autorisierung (`AuthorizationUseCase`)**: In-Memory Evaluierung via WASM anhand des `AuthContext` (inkl. extrahierter Rollen) gegen vorkompilierte `.rego` Regeln.
4.  **✅ Response**: Bei Erfolg antwortet der Sidecar mit `200 OK` und den Enrichment-Headern (`X-Auth-User-Id`, `X-Enriched-Roles`). Das Ingress-Gateway leitet den originalen Request dann an das Backend weiter.

### 👤 UserInfo Endpoint (`/userinfo`)

Ergänzend zur reinen Ingress-Autorisierung (via `/authorize`) stellt der Sidecar den `/userinfo` Endpunkt bereit.
Dieser durchläuft **exakt dieselbe Pipeline** (Authentifizierung → Rollen-Enrichment → OPA-Policy Check), leitet den Request aber am Ende nicht weiter, sondern gibt die aggregierten Berechtigungsdaten als strukturiertes JSON an den Aufrufer (z.B. ein Frontend) zurück.

```json
{
  "sub": "20d88f6b-3135-4dbb-887f-e2518e38d745",
  "preferred_username": "alice",
  "roles": ["view-profile", "shop-manager"],
  "permissions": {
    "store": ["read", "write"]
  }
}
```

---

## ⚙️ Konfiguration

Über Umgebungsvariablen lässt sich der Sidecar flexibel anpassen:

| Variable | Beschreibung | Standard |
|----------|--------------|----------|
| `OIDC_AUTH_SERVER_URL` | OIDC-Endpunkt (Discovery) | `http://localhost:8090/realms/master` |
| `OIDC_CLIENT_ID` | Client Identifier | `k8s-auth-sidecar` |
| `AUTHZ_ENABLED` | Policy-Prüfung ein/aus | `true` |
| `OPA_WASM_PATH` | Pfad zur OPA-WASM Datei | `classpath:policies/policy.wasm` |
| `OPA_HOT_RELOAD_INTERVAL` | Intervall für Hot-Reload der Policies | `10s` |

---

## 🚢 Kubernetes Deployment

Der Sidecar wird als `ext_authz`-Endpunkt neben deiner App deployt. Das Ingress-Gateway ruft `/authorize` auf dem Sidecar auf:

```yaml
spec:
  containers:
    - name: my-application
      image: my-app:latest
      ports: [{containerPort: 8085}]

    - name: k8s-auth-sidecar
      image: ghcr.io/maatini/k8s-auth-sidecar:0.3.0
      env:
        - name: OIDC_AUTH_SERVER_URL
          value: "https://keycloak.example.com/realms/myrealm"
      # Configure your ingress/envoy to call /authorize on this container.
```

---

## 🧪 So testest du das Projekt – Schritt für Schritt (super einfach erklärt)

Hier erfährst du, wie du sicherstellst, dass alles perfekt läuft. Wir gehen von "sehr einfach" bis zu "Profi-Check".

#### 1. Lokale Entwicklung (am schnellsten)
**Was du tust:** Starte den Sidecar im Entwicklungs-Modus.
- **Befehl:** `mvn quarkus:dev`
- **Was du sehen solltest:** Quarkus startet in wenigen Sekunden. Du siehst "Profile dev activated".
- **Warum das wichtig ist:** Du kannst Code ändern und siehst die Auswirkungen sofort (Hot Reload).

> [!TIP]
> **Junior-Tipp:** Nutze die Taste `r` im Terminal, um alle Tests im Dev-Modus manuell neu zu triggern!

#### 2. Unit- & POJO-Tests (ohne Quarkus-Start)
**Was du tust:** Führe die reinen Logik-Tests aus.
- **Befehl:** `mvn test -Dtest=*PojoTest`
- **Was du sehen solltest:** Eine Liste von Tests, die in Millisekunden durchlaufen.
- **Warum das wichtig ist:** Diese Tests sind super schnell und prüfen die "Business Logic", ohne dass das ganze Framework hochfahren muss.

#### 3. Vollständige Integrationstests (mit WireMock)
**Was du tust:** Teste das Zusammenspiel mit OIDC und Roles-Service.
- **Voraussetzung:** `docker compose -f docker-compose.dev.yml up -d`
- **Befehl:** `mvn verify`
- **Was du sehen solltest:** Quarkus fährt hoch, kontaktiert den (gemockten) Keycloak und führt echte HTTP-Requests aus.
- **Warum das wichtig ist:** Das ist der "Realitäts-Check" vor dem Deployment.

#### 4. Policy-Tests (opa test)
**Was du tust:** Prüfe deine Sicherheitsregeln (`.rego` Dateien).
- **Befehl:** `opa test opa-wasm/src/main/resources/policies/ -v` (wenn OPA installiert ist)
- **Was du sehen solltest:** `PASS` für alle definierten Regeln.
- **Warum das wichtig ist:** So verhinderst du, dass du dich versehentlich selbst aussperrst oder Sicherheitslücken einbaust. Lokale Kompilierung zu WASM: `opa build -t wasm -e authz/allow authz.rego`.

#### 5. Docker-Image bauen & testen
**Was du tust:** Erstelle ein fertiges Container-Image.
- **Befehl (JVM):** `docker build -t auth-sidecar:latest .`
- **Befehl (Native):** `docker build -f Dockerfile.native -t auth-sidecar:native .`
- **Was du sehen solltest:** Ein fertiges Docker-Image in deiner lokalen Liste (`docker images`).
- **Warum das wichtig ist:** So läuft der Sidecar später auch in Kubernetes.

#### 6. Kubernetes-Deployment testen (kustomize)
**Was du tust:** Generiere die finalen YAML-Dateien für K8s.
- **Befehl:** `kubectl kustomize k8s/overlays/development`
- **Was du sehen solltest:** Einen langen Output mit allen Kubernetes-Ressourcen (Deployments, Services, ConfigMaps).
- **Warum das wichtig ist:** Du siehst genau, was auf dem Cluster landen würde, bevor du `kubectl apply` machst.

#### 7. Mutation Testing (PIT) – Qualitäts-Check
**Was du tust:** Lasse die "Test-Polizei" über deinen Code laufen.
- **Befehl:** `mvn org.pitest:pitest-maven:mutationCoverage`
- **Was du sehen solltest:** Einen HTML-Report in `target/pit-reports/`.
- **Warum das wichtig ist:** PIT verändert deinen Code absichtlich ("Mutanten"). Wenn deine Tests das nicht merken, sind sie nicht gut genug. Wir zielen auf **> 70% Test Strength**!

> [!IMPORTANT]
> **Junior-Tipp:** Wenn PIT eine Mutation nicht findet, überlege dir einen Edge-Case (z.B. "Was passiert, wenn der Header leer ist?"), den du noch nicht getestet hast.

#### 8. Aktuelle Test-Metriken (gemessen 2026-03-17)

Das Projekt besitzt eine extrem schnelle, überwiegend Framework-unabhängige Test-Suite.

| Modul       | `@Test`-Methoden | Failures | Typ                  |
|-------------|-----------------:|:--------:|----------------------|
| `config`    | 4                | 0 ✅     | POJO + Quarkus       |
| `auth-core` | 13               | 0 ✅     | POJO                 |
| `opa-wasm`  | 6                | 0 ✅     | POJO                 |
| `ext-authz` | 33               | 0 ✅     | POJO + Quarkus       |
| **Gesamt**  | **56**           | **0 ✅** |                      |

**PIT Scores per Module:**

| Module      | Killed % | Strength % | Line Cov % |
|-------------|----------|------------|------------|
| config      | 29       | 100        | 53         |
| auth-core   | 51       | 80         | 74         |
| opa-wasm    | 66       | 70         | 88         |
| ext-authz   | 50       | 79         | 53         |

- Bericht: `<modul>/target/pit-reports/index.html`

**Begriffsklärung:**
- **Line Coverage**: Wie viel Prozent des Codes wurden mindestens einmal ausgeführt?
- **Test Strength**: Wie viele künstliche Fehler (Mutanten) wurden von den Tests gefunden? — Unser wichtigster Qualitätsindikator.

---

---

## 📂 Projektstruktur (Maven Multi-Module)

- `auth-core/`: Domain-Modelle, Request-Processor und Auth-Logik.
- `ext-authz/`: Vert.x Route Handler (`/authorize`) und JAX-RS Filter.
- `opa-wasm/`: OPA WASM Engine und Rego-Policies (`.rego`).
- `config/`: Quarkus-Config, Metriken und Health-Checks.
- `k8s/`: Kustomize Manifeste für das Deployment.

---

## 🛡️ Sicherheit & Performance

- **Reaktive Pipeline**: Non-blocking Autorisierungsentscheidungen via Mutiny `Uni` und Vert.x Route Handler.
- **WASM Hot-Reload**: Ersetze Policies im laufenden Betrieb ohne Downtime. Pool-Größe konfigurierbar via `OPA_POOL_SIZE` (Default: 50).
- **Fault Tolerance**: `RolesService` nutzt `@Timeout(200ms)`, `@CircuitBreaker` und `@Fallback` (Fail-Open: bei Ausfall werden nur JWT-Rollen verwendet).
- **⚠️ Durchsatz-Hinweis (> 1000 RPS)**: Bei hoher Last (> 1000 Req/s) wurden in Lasttests Engpässe identifiziert (Event-Loop Blockade, Connection-Pool-Limit, synchrones Logging). Die Architektur ist bereits auf deren Beseitigung ausgelegt; die konkreten Optimierungen sind in [`docs/ARCHITECTURE.md` → Roadmap](docs/ARCHITECTURE.md#-performance--production-readiness-roadmap) dokumentiert. **Für den POC ist dies irrelevant.**

---

## 📄 Lizenz & Kontakt

Apache License 2.0 - siehe [LICENSE](LICENSE).

Entwickelt von **[de.edeka.eit](https://github.com/maatini)**
