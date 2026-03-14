# K8s-Auth-Sidecar – Der einfache AuthN/AuthZ-Sidecar für Kubernetes

Hallo Junior-Entwickler! 🎉  
Stell dir vor, du baust eine coole Web-App (z. B. mit Spring Boot, Quarkus oder was auch immer). Plötzlich musst du dich um **Login**, **Rechte** und **Sicherheit** kümmern – für **jeden** Request. Das wird schnell kompliziert und du willst deine Haupt-App nicht mit diesem Kram „verschmutzen“.  

Genau dafür gibt es den **k8s-auth-sidecar**!

---

## 🐦 Vogelperspektive – Was ist das eigentlich?

Der Sidecar ist ein **kleiner Helfer-Container**, der **neben** deiner eigentlichen Anwendung im selben Kubernetes-Pod läuft (Sidecar-Pattern).

- Jeder HTTP-Request von außen landet zuerst bei einem Ingress-Gateway (z. B. Envoy).
- Das Gateway fragt den Sidecar: „Darf dieser User das überhaupt?“ (Port 8080 `/authorize`).
- Wenn ja → leitet das Gateway den Request mit neuen Sicherheits-Headern weiter an deine App.
- Wenn nein → schickt das Gateway sofort `401` oder `403` zurück. Deine App merkt davon **gar nichts**!

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
   - Die Antworten auf diese Rollenabfrage sowie die aufwändige JWT-Validierung selbst werden mittels eines **Caffeine-Caches in-Memory** zwischengespeichert ($O(1)$). Ersatz-Anfragen kosten also so gut wie gar keine Zeit.

3. **Regel-Prüfung (AuthZ mit OPA)**  
   - Der Sidecar baut ein JSON mit allen Infos (User, Rollen, Request-Methode, Pfad …).  
   - Er fragt die **OPA-Engine** (Open Policy Agent) – eine super mächtige Regel-Engine.  
   - Die Regeln stehen in Dateien mit der Endung `.rego` (eine eigene, einfache Sprache namens Rego).  
   - Beispiel: „Wenn der User `admin` ist UND der Pfad mit `/api/admin` beginnt → erlauben“.

4. **Weiterleitung oder Blockieren (Proxy)**  
   - Erlaubt? → Request wird an `localhost:8085` (deine App) weitergeleitet. Zusätzlich werden nützliche Header mitgeschickt:
     - `X-Auth-User-Id`: Die User-ID (Subject).
     - `X-Enriched-Roles`: Komma-separierte Liste der angereicherten Rollen.
   - Verboten? → sofort `403 Forbidden` an den Client. Deine App wird **nie** aufgerufen → super performant und sicher!

---

## 🛠️ Wichtige Konzepte, die du als Junior kennen solltest

- **Sidecar-Pattern**: Zwei Container im selben Pod teilen sich Netzwerk und Storage. Der eine hilft dem anderen.
- **Zero-Trust**: Niemandem wird automatisch vertraut – jeder Request wird geprüft.
- **JWT (JSON Web Token)**: Ein verschlüsseltes „Ausweis-Kärtchen“ mit User-Infos.
- **Hot-Reload**: Du änderst eine `.wasm` Datei in einer ConfigMap → der Sidecar merkt es und lädt sie neu, ohne Neustart!
- **OPA Workflow**: Schreibe Rego, kompiliere lokal zu WASM (`opa build -t wasm ...`) und mounte die `.wasm` Datei.
- **Quarkus**: Ein super-schnelles Java-Framework, das auch als winziges **Native-Image** (keine JVM nötig) laufen kann.
- **Reaktives Streaming**: Der Sidecar streamt selbst riesige Payloads (z. B. 500 MB Dateiuploads) ohne RAM-Probleme. Die Connection-Pools für solche Weiterleitungen (Proxy) sind dynamisch konfigurierbar.
- **Micro-Caching**: Um bei jedem Nutzer nicht ständig aufwendig Kryptografie prüfen zu müssen, behält der Sidecar verifizierte Ausweise kurz im Gedächtnis (JWT Caffeine Cache).

---

## ✨ Alle Features im Überblick

- Unterstützt **Keycloak** und **Entra ID** (auch Multi-Tenant)
- **Embedded OPA WASM** (In-Memory) – Lädt vorkompilierte `.wasm` Policies für maximale Speed.
- Rollen-Enrichment aus eigenem Service
- **Reaktive Pipeline** (Mutiny `Uni`) und **Streaming-Proxy** (Vert.x) – minimale Speicherbelegung!
- Ant-Style Path-Matching (`/**`, `/api/*/users`)
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

## 🧪 So testest du das Projekt – Schritt für Schritt (super einfach erklärt)

Keine Angst vor den 118 Tests! Wir zeigen dir jetzt, wie du sie alle ausführst und vor allem: **Was sie bedeuten.**

#### 1. Lokale Entwicklung (am schnellsten)
Wenn du gerade am Code bastelst, willst du sofort sehen, ob es noch klappt.
- **Befehl:** `mvn quarkus:dev`
- **Was du siehst:** Quarkus startet. Wenn du im Browser `http://localhost:8080/q/dev` aufrufst, siehst du das "Dashboard".
- **Junior-Tipp:** Drück mal `r`. Das startet alle Tests im Hintergrund neu. Wenn alles grün ist, hast du nichts kaputt gemacht!

#### 2. Unit- & POJO-Tests (ohne Quarkus-Start)
POJO steht für "Plain Old Java Object". Das sind Tests, die extrem schnell sind, weil sie kein schweres Framework brauchen.
- **Befehl:** `mvn test -Dtest=*PojoTest`
- **Was du siehst:** Ein grüner Balken in Millisekunden.
- **Was passiert jetzt?** Wir prüfen nur die Logik-Klassen (z.B. den `PathMatcher`), ohne dass ein Server gestartet wird.
- **Junior-Tipp:** Schreib diese Tests zuerst! Sie helfen dir, die Logik deiner Klasse zu verstehen, bevor du dich mit Kubernetes-Kram rumschlägst.

#### 3. Vollständige Integrationstests (mit WireMock)
Jetzt wird es ernst. Wir prüfen, ob der Sidecar wirklich mit Keycloak (unserem Login-Server) sprechen kann.
- **Voraussetzung:** Starte die Mocks mit `docker compose -f docker-compose.dev.yml up -d`.
- **Befehl:** `mvn verify`
- **Warum das wichtig ist:** Wir simulieren hier echte Requests. Wenn dieser Test besteht, ist deine App fast bereit für die Cloud!

#### 4. Policy-Tests (opa test)
Unsere Sicherheitsregeln (in Rego geschrieben) müssen auch getestet werden.
- **Befehl:** `opa test opa-wasm/src/main/resources/policies/ -v`
- **Erklärung:** Hier schauen wir: "Darf der User 'Bob' wirklich nicht auf '/admin'?"
- **Junior-Tipp:** Wenn du eine neue Regel schreibst, schreib IMMER auch einen Test dazu in die gleiche Ordner-Struktur.

#### 5. Docker-Image bauen & testen
Bevor wir etwas nach Kubernetes schieben, verpacken wir es in einen Container.
- **Befehl:** `docker build -t sidecar-test:1.0 .`
- **Qualitätscheck:** Wir nutzen im Hintergrund Tools wie **Trivy**, um zu schauen, ob unser Image Sicherheitslücken hat.
- **Junior-Tipp:** Native Images (`Dockerfile.native`) bauen länger (bis zu 5 Min!), sind aber im Cluster viel kleiner und schneller.

#### 6. Kubernetes-Deployment testen (kustomize)
Bevor du `kubectl apply` tippst, schau dir an, was passieren würde.
- **Befehl:** `kubectl kustomize k8s/overlays/development`
- **Was passiert jetzt?** Kustomize baut alle deine "Puzzleteile" (YAML-Dateien) zu einer großen Datei zusammen.
- **Junior-Tipp:** Prüf hier besonders, ob die `PROXY_TARGET_PORT` auf deine App zeigt!

#### 7. Mutation Testing (PIT) – Qualitäts-Check
Das ist das "Nächste Level". PIT verändert deinen Code absichtlich ("Mutanten"), um zu sehen, ob deine Tests das merken.
- **Befehl:** `mvn org.pitest:pitest-maven:mutationCoverage`
- **Ergebnis:** Du findest einen Report in `target/pit-reports/`.
- **Junior-Tipp:** Wenn ein Mutant "überlebt" (Survived), heißt das: Dein Test deckt diesen speziellen Fall noch nicht ab. Probier mal, den Fehler absichtlich einzubauen – dein Test sollte dann fehlschlagen!

#### 8. Was bedeuten die Test-Zahlen? (kurze Erklärung für Juniors)
Wenn wir von **80% Test Strength** sprechen, meinen wir: Von allen künstlichen Fehlern, die wir eingebaut haben, haben deine Tests 80% gefunden. Das ist für ein Junior-Projekt ein **exzellenter Wert**!

---

## 🔐 Sicherheit & Best Practices (kurz & wichtig)

Sicherheit klingt langweilig? Nicht hier! Mit dem Sidecar bist du von Anfang an ein Profi:
- **Least Privilege**: Gib einem User immer nur so viel Rechte, wie er wirklich braucht.
- **Don't hardcode**: Nutze niemals Passwörter im Code. Dafür gibt es `application.properties` und Kubernetes Secrets.
- **Watch the Logs**: Schau dir mit `kubectl logs` an, was dein Sidecar macht. Er sagt dir genau, warum ein Request abgelehnt wurde.

---

## ⚠️ Bekannte Fallstricke & Performance-Warnungen

Auch wenn der Sidecar super schnell ist, gibt es zwei Dinge, auf die du achten musst:

1. **The Event Loop Blockade (CPU-Last)**: 
   Die Verarbeitung von JWTs (Kryptografie) und das Parsen von JSON sind CPU-intensiv. Wenn wir das direkt auf dem "Event Loop" (dem Haupt-Förderband von Vert.x) machen, bleibt der Sidecar stehen. **Lösung**: Wir nutzen `@Blocking` oder `Uni.emitOn(Infrastructure.getDefaultWorkerPool())`, um diese Arbeit auf andere Threads auszulagern.

2. **The WASM Pool Race Condition**: 
   Die OPA-Logik läuft in einer WASM-Engine. Da eine WASM-Instanz immer nur einen Request gleichzeitig verarbeiten kann, nutzen wir einen **Pool**. Wenn dein Pool zu klein ist (z. B. nur 10 Instanzen), aber 100 Leute gleichzeitig kommen, müssen 90 warten. Achte darauf, den Pool in der `application.properties` passend zu deiner Last zu konfigurieren.

> [!IMPORTANT]
> **POJO-First Rule**: Um eine Mutation-Strength von > 80% zu halten, muss die Kernlogik in `auth-core` immer in reinen Java-Klassen (POJOs) ohne Framework-Schnickschnack bleiben. Nur so findet PIT wirklich alle Logikfehler!

> [!TIP]
> **Einfachheit ist Trumpf:** Wenn du merkst, dass deine Rego-Regeln zu kompliziert werden, sprich sie nochmal mit einem Senior durch. Meistens gibt es einen einfacheren Weg!

---

## 📊 Projekt-Reife & Setup

- **Sehr stabil**: Komplettes Refactoring (reaktiv + streaming + Memory-Optimierung) extrem performant.
- **Aktiv in Entwicklung**: Core-Funktionen (Auth-Filter, Proxy, OPA, Path-Matcher) inkl. serverseitigen Caffeine-Caches (Session & Profiling) sind produktionsreif.
- **Testing & Mutation Score**: **118 automatisierte Tests** (POJO+ExtTests + QuarkusTests). Die Kernservices in `auth-core` erreichen **82% PIT Test Strength** und **91% PIT Line Coverage**. Proxy-QuarkusIntegrationstests benötigen den lokalen WireMock-Stack (`docker-compose.dev.yml`).
- **Dokumentation**: Sehr stark! README + `docs/ARCHITECTURE.md` + dieser Junior Guide.
## 🛡️ Die zwei Gesichter des Sidecars

Du kannst den Sidecar auf zwei Arten nutzen, je nachdem, was du brauchst:

### 1. Der "Leibwächter" (Proxy Mode)
Das ist der Standard. Der Sidecar steht direkt vor deiner App. Alles, was zu deiner App will, muss erst am Sidecar vorbei. Er prüft den Ausweis (JWT) und lässt den Request nur durch, wenn alles okay ist.
- **Wann nutzen?** Wenn deine App selbst gar nichts von Auth wissen soll.

### 2. Der "Ausweis-Prüfer" (Gateway Mode / ext_authz)
Hier steht der Sidecar nicht direkt vor deiner App, sondern daneben. Ein großes Gateway (z.B. Envoy oder Nginx) fragt den Sidecar über den `/authorize` Endpunkt: "Hey, dieser Typ hier will rein, ist sein Ausweis okay?". Der Sidecar sagt "Ja" oder "Nein", und das Gateway lässt den Typen dann durch oder blockt ihn ab.
- **Wann nutzen?** Wenn du ein zentrales Gateway (Ingress) hast, das die Arbeit macht, aber die Logik vom Sidecar nutzen soll.

---

## 🏗️ Wie der Sidecar im Inneren tickt (für Neugierige)

Der Sidecar ist für ernsthafte Einsätze konzipiert und skaliert logisch und fehlerfrei im Kubernetes Cluster.

---

## 🎯 Warum ist das genial für dich?

Du lernst hier nicht nur Java, sondern die gesamte **Cloud-Native Welt**:
1.  **Container** (Docker)
2.  **Orchestrierung** (Kubernetes)
3.  **Modernes Security-Design** (Zero-Trust, OIDC, OPA)
4.  **Hochmoderner Java-Stack** (Quarkus, Mutiny, GraalVM)

Du baust nicht nur eine App, sondern ein sicheres System!

---

## 🚀 Die „Antigravity Migration“ – Von Monolith zu Multi-Module

Im März 2026 wurde das Projekt durch eine umfassende Refaktorisierung (die „Antigravity Migration“) auf ein neues Level gehoben. Warum haben wir das gemacht?

**Das Problem vorher:**
Alles lag in einem großen Topf (`src/main/java`). Wenn man etwas an der OPA-Engine geändert hat, musste man aufpassen, nicht versehentlich den Proxy oder die Config zu zerschießen. Das nennt man „starke Kopplung“.

**Die Lösung (Multi-Module):**
Das Projekt wurde in 4 spezialisierte Module aufgeteilt:
1.  **`auth-core`**: Das Gehirn. Hier liegen die Regeln, wer ein User ist und wie Anfragen verarbeitet werden.
2.  **`proxy`**: Der Muskel. Er kümmert sich nur um das effiziente Weiterleiten von Daten.
3.  **`opa-wasm`**: Der Bibliothekar. Er verwaltet die Sicherheitsregeln (Policies).
4.  **`config`**: Die Zentrale. Hier wird alles eingestellt und überwacht.

**Was du daraus lernst:**
-   **Modularität**: Wenn du ein Modul änderst, bleiben die anderen stabil.
-   **Dependency Inversion**: Wir arbeiten mit Interfaces. Der Proxy weiß z.B. nur, *dass* es einen `AuthContext` gibt, aber nicht, wie er technisch aus dem JWT extrahiert wurde.
-   **Clean Architecture**: Jedes Modul hat seine eigenen Ebenen (`domain`, `application`, `infrastructure`). Das macht den Code extrem übersichtlich.

---

**Zusammenfassung in einem Satz:**  
Der k8s-auth-sidecar ist ein schlanker, hochperformanter „Türsteher“ für deine Kubernetes-Apps, der alle Sicherheitsfragen übernimmt, damit du dich voll auf deine Business-Logik konzentrieren kannst.

Viel Spaß beim Ausprobieren!  
Falls du Fragen hast oder mitentwickeln möchtest – das Repo ist frisch und offen. 💪
