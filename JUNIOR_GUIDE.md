# K8s-Auth-Sidecar – Der einfache AuthN/AuthZ-Sidecar für Kubernetes

Hallo Junior-Entwickler! 🎉  
Stell dir vor, du baust eine coole Web-App (z. B. mit Spring Boot, Quarkus oder was auch immer). Plötzlich musst du dich um **Login**, **Rechte** und **Sicherheit** kümmern – für **jeden** Request. Das wird schnell kompliziert und du willst deine Haupt-App nicht mit diesem Kram „verschmutzen“.  

Genau dafür gibt es den **k8s-auth-sidecar**!

---

## 🐦 Vogelperspektive – Was ist das eigentlich?

Der Sidecar ist ein **kleiner Helfer-Container**, der **neben** deiner eigentlichen Anwendung im selben Kubernetes-Pod läuft (Sidecar-Pattern).

- Dein Service im Cluster zeigt **nicht** mehr direkt auf deine App (Port 8081), sondern auf den **Sidecar** (Port 8080).  
- Jeder HTTP-Request von außen landet zuerst beim Sidecar.  
- Der Sidecar prüft: „Darf dieser User das überhaupt?“  
- Wenn ja → leitet er den Request weiter an deine App (lokal über `localhost:8081`).  
- Wenn nein → schickt er sofort `401` oder `403` zurück. Deine App merkt davon **gar nichts**!

**Vorteile für dich als Junior:**
- Deine Haupt-App bleibt **sauber** und kümmert sich nur um Business-Logik.  
- Sicherheit ist **zentral** und einheitlich für alle Microservices.  
- Du kannst Regeln ändern, ohne deine App neu zu bauen oder neu starten.

---

## 🔍 Detaillierter Ablauf – Was passiert bei jedem Request? (Schritt für Schritt)

Der Sidecar ist wie ein **Sicherheits-Checkpoint** auf einem Flughafen:

1. **Token Validierung (AuthN)**  
   - Der Client schickt `Authorization: Bearer eyJ...` mit.  
   - Der Sidecar prüft das JWT gegen **Keycloak** oder **Microsoft Entra ID** (Azure AD).  
   - Prüfungen: Signatur richtig? Noch nicht abgelaufen? Für meine App ausgestellt? Vom richtigen Server?  
   - Bei Fehler → sofort `401 Unauthorized`.

2. **Rollen-Anreicherung (Enrichment)**  
   - Im JWT stehen oft nur grobe Rollen (z. B. „user“).  
   - Der Sidecar fragt einen separaten **Roles-Service** (dein eigener Microservice): „Hey, was darf user-123 noch alles in Projekt-X?“  
   - Die Antworten auf diese Rollenabfrage sowie die aufwändige JWT-Validierung selbst werden mittels eines **Caffeine-Caches in-Memory in Sekundenbruchteilen** zwischengespeichert ($O(1)$). Ersatz-Anfragen kosten also so gut wie gar keine Zeit.

3. **Regel-Prüfung (AuthZ mit OPA)**  
   - Der Sidecar baut ein JSON mit allen Infos (User, Rollen, Request-Methode, Pfad …).  
   - Er fragt die **OPA-Engine** (Open Policy Agent) – eine super mächtige Regel-Engine.  
   - Die Regeln stehen in Dateien mit der Endung `.rego` (eine eigene, einfache Sprache namens Rego).  
   - Beispiel: „Wenn der User `admin` ist UND der Pfad mit `/api/admin` beginnt → erlauben“.

4. **Weiterleitung oder Blockieren (Proxy)**  
   - Erlaubt? → Request wird an `localhost:8081` (deine App) weitergeleitet. Zusätzlich werden nützliche Header mitgeschickt (z. B. `X-Auth-User-Roles`).  
   - Verboten? → sofort `403 Forbidden` an den Client. Deine App wird **nie** aufgerufen → super performant und sicher!

---

## 🛠️ Wichtige Konzepte, die du als Junior kennen solltest

- **Sidecar-Pattern**: Zwei Container im selben Pod teilen sich Netzwerk und Storage. Der eine hilft dem anderen.
- **Zero-Trust**: Niemandem wird automatisch vertraut – jeder Request wird geprüft.
- **JWT (JSON Web Token)**: Ein verschlüsseltes „Ausweis-Kärtchen“ mit User-Infos.
- **OPA + Rego**: Die „Gesetzbücher“ deiner App. Du schreibst Regeln in Rego, kompilierst sie zu WASM und der Sidecar entscheidet in Millisekunden In-Memory.
- **Hot-Reload**: Du änderst eine `.wasm` oder `.rego`-Datei in einer ConfigMap → der Sidecar merkt es und lädt sie neu, ohne Neustart!
- **Quarkus**: Ein super-schnelles Java-Framework, das auch als winziges **Native-Image** (keine JVM nötig) laufen kann.
- **Reaktives Streaming**: Der Sidecar blockiert keine Threads und streamt selbst riesige Payloads (z. B. 500 MB Dateiuploads) ohne RAM-Probleme. Die Connection-Pools für solche Weiterleitungen (Proxy) sind dynamisch konfigurierbar.
- **Micro-Caching**: Um bei jedem Nutzer nicht ständig aufwendig Kryptografie prüfen zu müssen, behält der Sidecar verifizierte Ausweise kurz im Gedächtnis (JWT Caffeine Cache).

---

## ✨ Alle Features im Überblick

- Unterstützt **Keycloak** und **Entra ID** (auch Multi-Tenant)
- **Embedded OPA WASM** (In-Memory, enorm schnell) oder externer OPA-Server, jetzt mit **v1.x OPA CLI** im Container für automatisches `.rego`-Kompilieren
- Rollen-Enrichment aus eigenem Service
- **Vollständig reaktive Pipeline** (Mutiny `Uni`) und **Streaming-Proxy** (Vert.x) – minimale Speicherbelegung!
- Ant-Style Path-Matching (`/**`, `/api/*/users`)
- **Rate-Limiting** mit Caffeine-Cache und Schutz vor IP-Spoofing
- Prometheus-Metriken + OpenTelemetry + JSON-Logging
- Health-Checks (liveness/readiness)
- GraalVM Native Image (sehr klein & schnell)
- Fertige Kustomize-Manifeste für Kubernetes (inklusive striktem Zero-Trust `securityContext`)
- Vollständige `@PreDestroy`-Aufräumarbeiten (sauberer Shutdown)
- **Gehärtete Docker-Images** (Trivy-CVE-Scans, smarte CI-Caching-Pipeline, distroless-Ansätze)

---

## 📦 Wie kommst du schnell los? (Copy-Paste für Juniors)

```bash
# 1. Projekt klonen
git clone https://github.com/maatini/k8s-auth-sidecar.git
cd k8s-auth-sidecar

# 2. Lokal starten (Entwicklungsmodus)
mvn quarkus:dev

# 3. In Kubernetes (Beispiel)
kubectl apply -k k8s/overlays/development
```

Danach nur noch:
- Deinen bestehenden Deployment um den Sidecar-Container erweitern
- Service auf Port `8080` des Sidecars zeigen lassen
- ConfigMap mit deinen `.rego`-Policies anlegen

---

## 🧪 Schritt-für-Schritt: Den Sidecar lokal testen

Lass uns in 4 schnellen Schritten sehen, wie der Sidecar aus der Perspektive eines Clients reagiert. Stell sicher, dass du wie im Schritt zuvor den Entwicklungsmodus (`mvn quarkus:dev`) und WireMock via Docker Compose am Laufen hast.

### 1. Token holen
Wir sagen dem WireMock-Server: *"Gib mir einen Ausweis für den Test-User!"*
```bash
export TOKEN=$(curl -s -X POST http://localhost:8090/realms/master/protocol/openid-connect/token | jq -r .access_token)
```

### 2. Szenario A: Der "Gute" Request (Erlaubt / 200 OK)
Wir rufen einen Endpunkt auf. Unser Test-User bekommt vom gemockten Roles-Service standardmäßig die Rollen `admin` und `developer`. Wenn deine `.rego`-Policy Admin-Rechte verlangt, wird das hier klappen.
```bash
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/dashboard
```
**Was passiert?**
1. Sidecar sieht: Token gültig ✅
2. Sidecar fragt Roles-Service: User hat Rolle `admin` ✅
3. Sidecar fragt OPA-Engine: Regel sagt "admin darf das" ✅
4. Sidecar schickt es ans Backend weiter. Das Backend antwortet mit `200 OK`.

### 3. Szenario B: Der "Böse" Request (Abgelehnt / 403 Forbidden)
Nehmen wir an, in deinen OPA-Regeln (`.rego`) steht explizit: *Niemand darf auf `/api/geheim` zugreifen, außer der Superadmin.* Da unser Test-User nur `admin` ist, schlägt dies fehl.
```bash
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/geheim
```
**Was passiert?**
1. Token gültig ✅
2. Rollen abgefragt ✅
3. OPA-Engine sagt: Regel verbietet Zugriff ❌
4. **Ergebnis:** Du bekommst sofort ein `HTTP 403 Forbidden` zurück. Die eigentliche App (`localhost:8081`) kriegt von diesem Request **absolut nichts mit**!

### 4. Szenario C: Ohne gültigen "Ausweis" (Unautorisiert / 401 Unauthorized)
Was passiert, wenn ein Hacker versucht, ohne Ticket durch den Türsteher zu kommen?
```bash
curl -i http://localhost:8080/api/irgendwas
# oder auch mit ungültigem Token:
curl -i -H "Authorization: Bearer FALSCHER_TOKEN_123" http://localhost:8080/api/irgendwas
```
**Was passiert?**
Der Sidecar blockt das sofort an der Vordertür ab! Du erhältst `HTTP 401 Unauthorized` und die Prüfung wird direkt abgebrochen.

---

## 🔐 Sicherheit & Best Practices (kurz & wichtig)

- Immer Secrets über Kubernetes Secrets einbinden
- In Produktion `QUARKUS_HTTP_CORS_ORIGINS` einschränken
- Regeln nach „Least Privilege“ schreiben (so wenig Rechte wie möglich)
- Audit-Logging aktivieren → du siehst später genau, wer was wollte
- Vertrauenswürdige IP-Adressen für Proxies (Load Balancer/Ingress) konfigurieren, um IP-Spoofing zu verhindern

---

## 📊 Projekt-Reife & Setup

- **Sehr stabil**: Komplettes Refactoring (reaktiv + streaming + Memory-Optimierung) extrem performant. Null Objekt-Allokationen bei Fehler-Fallbacks.
- **Aktiv in Entwicklung**: Core-Funktionen (Auth-Filter, Proxy, OPA, Path-Matcher) inkl. serverseitigen Caffeine-Caches (Session & Profiling) sind produktionsreif.
- **Testing & Mutation Score**: Über **140 automatisierte Tests** mit über >80% Branch Coverage. Die Kernservices bewältigen **PIT Mutation Tests** mit einem Kill-Score von >85%.
  - *Junior-Tipp:* Teste Kernlogik immer ohne Framework (`@QuarkusTest`), also als reines Java-Objekt (POJO). Das ist extrem schnell und deckt kleinste Mutanten auf (z.B. Mockito Spy Maps für Edge-Cases)!
- **Dokumentation**: Sehr stark! README + `docs/ARCHITECTURE.md` + dieser Junior Guide.
- **Fazit**: Der Sidecar ist bereit für ernsthafte Einsätze und skaliert logisch und fehlerfrei im Kubernetes Cluster.

---

## 🎯 Warum ist das genial für Junior-Entwickler?

Du lernst auf einmal:
- Kubernetes Sidecar-Pattern
- Moderne AuthN/AuthZ (OIDC + OPA)
- Reaktive, nicht-blockierende Filter in Quarkus
- Hot-Reload von Policies
- Observability (Metrics + Health) & Memory Management (Caffeine Cache)

Und deine Haupt-App bleibt so einfach wie `return "Hello World";` – die ganze Sicherheit macht der Sidecar!

---

**Zusammenfassung in einem Satz:**  
Der k8s-auth-sidecar ist ein schlanker, hochperformanter „Türsteher“ für deine Kubernetes-Apps, der alle Sicherheitsfragen übernimmt, damit du dich voll auf deine Business-Logik konzentrieren kannst.

Viel Spaß beim Ausprobieren!  
Falls du Fragen hast oder mitentwickeln möchtest – das Repo ist frisch und offen. 💪
