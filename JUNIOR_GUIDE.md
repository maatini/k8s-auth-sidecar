# K8s-Auth-Sidecar – Der einfache AuthN/AuthZ-Sidecar für Kubernetes
*(Stand: 20. Februar 2026 – sehr frühes, aber bereits lauffähiges Projekt)*

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
   - Antwort wird kurz **gecached** (schnell & ressourcenschonend).

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

---

## ✨ Alle Features im Überblick

- Unterstützt **Keycloak** und **Entra ID** (auch Multi-Tenant)
- **Embedded OPA WASM** (In-Memory, enorm schnell) oder externer OPA-Server
- Rollen-Enrichment aus eigenem Service
- Ant-Style Path-Matching (`/**`, `/api/*/users`)
- Prometheus-Metriken + OpenTelemetry + JSON-Logging
- Health-Checks (liveness/readiness)
- GraalVM Native Image (sehr klein & schnell)
- Fertige Kustomize-Manifeste für Kubernetes
- Vollständige `@PreDestroy`-Aufräumarbeiten (sauberer Shutdown)

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

## 🔐 Sicherheit & Best Practices (kurz & wichtig)

- Immer Secrets über Kubernetes Secrets einbinden
- In Produktion `QUARKUS_HTTP_CORS_ORIGINS` einschränken
- Regeln nach „Least Privilege“ schreiben (so wenig Rechte wie möglich)
- Audit-Logging aktivieren → du siehst später genau, wer was wollte

---

## 📊 Aktueller Stand des Projekts (20.02.2026)

- **Sehr jung**: Erste Commits am 07.02.2026, letzte Änderung am 13.02.2026  
- **Aktiv in Entwicklung**: Core-Funktionen (Auth-Filter, Proxy, OPA, Path-Matcher) sind bereits implementiert + erste Unit-Tests  
- **0 Stars / Forks / Issues**: Noch kein Community-Feedback, aber das Repository ist öffentlich und gut dokumentiert  
- **Technologie**: Java 21 + Quarkus 3.17, Maven, Docker (JVM + Native), Kustomize  
- **Dokumentation**: Sehr stark! README + `docs/ARCHITECTURE.md` mit Bildern, Tabellen und Beispielen  
- **Fazit**: Perfekt zum Mitmachen oder als Blaupause für eigene Sidecars. Die Grundfunktion steht, jetzt kommen wahrscheinlich Feinschliff, mehr Tests und vielleicht Helm-Charts.

---

## 🎯 Warum ist das genial für Junior-Entwickler?

Du lernst auf einmal:
- Kubernetes Sidecar-Pattern
- Moderne AuthN/AuthZ (OIDC + OPA)
- Reaktive, nicht-blockierende Filter in Quarkus
- Hot-Reload von Policies
- Observability (Metrics + Health)

Und deine Haupt-App bleibt so einfach wie `return "Hello World";` – die ganze Sicherheit macht der Sidecar!

---

**Zusammenfassung in einem Satz:**  
Der k8s-auth-sidecar ist ein schlanker, hochperformanter „Türsteher“ für deine Kubernetes-Apps, der alle Sicherheitsfragen übernimmt, damit du dich voll auf deine Business-Logik konzentrieren kannst.

Viel Spaß beim Ausprobieren!  
Falls du Fragen hast oder mitentwickeln möchtest – das Repo ist frisch und offen. 💪

*(Analyse basiert auf README.md, ARCHITECTURE.md, Commit-History und Projektstruktur vom 20.02.2026)*
