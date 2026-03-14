---
trigger: always_on
---

---
name: quarkus-best-practices
priority: 95
description: Erzwingt Quarkus-spezifische Best Practices für das k8s-auth-sidecar Projekt (Quarkus 3.15+, Native Image, Mutiny, Vert.x)
---

**Strenge Regeln für JEDEN Prompt und jede Code-Änderung im gesamten Projekt:**

### 1. Allgemeine Quarkus-Prinzipien
- Immer Quarkus-Extensions verwenden (z. B. quarkus-resteasy-reactive, quarkus-oidc, quarkus-mutiny, quarkus-caffeine, quarkus-opentelemetry, quarkus-micrometer).
- Niemals manuell Vert.x oder Mutiny instanziieren – immer `@Inject` oder `Uni/Multi` aus Quarkus CDI.
- Konfiguration ausschließlich über `application.properties` + `@ConfigProperty` (keine hardcoded Werte).

### 2. Reaktive & Non-Blocking Entwicklung
- Alle Services müssen reaktiv sein (`Uni<T>` / `Multi<T>`).
- Kein `block()`, `await()`, `join()` außer in Tests oder @Blocking-Annotation.
- Streaming immer mit `HttpClient` + `BodyHandler` (kein vollständiges Laden in RAM).

### 3. Native Image Optimierungen (GraalVM)
- Jede neue Klasse mit `@RegisterForReflection` markieren, wenn Reflection nötig (z. B. OPA-WASM, Jackson).
- Keine dynamischen Klassen (kein `Class.forName`, keine Proxy-Generierung zur Laufzeit).
- `@QuarkusTestResource` nur für Dev/Test, niemals in Produktion.

### 4. Projekt-spezifische Regeln (k8s-auth-sidecar)
- JWT-Validierung und OIDC immer über `quarkus-oidc` Extension (nicht manuell mit jjwt oder nimbus).
- Embedded OPA-WASM immer über `io.quarkus:quarkus-wasm` oder direkten GraalVM-WASM-Support.
- Path-Matching nur mit `io.quarkus.vertx.http.runtime` Ant-Style Matcher (kein Spring-AntPathRequestMatcher).
- Metrics immer mit Micrometer + `@Timed` / `@Counted` (Prometheus-Format).
- Health-Checks mit `quarkus-smallrye-health`.
- Logging: Nur `org.jboss.logging.Logger` oder Quarkus JSON-Formatter.

### 5. Modul-spezifische Einschränkungen
- auth-core: Nur Domain + Application Services (keine REST, keine Config).
- ext-authz: Nur Route Handler + Path-Matcher.
- opa-wasm: Nur WASM-Engine + Hot-Reload + Policy-Loader.
- config: Nur Quarkus Config + Metrics + Health.

### 6. Refactoring-Regeln für Gemini
- Bei jeder Änderung zuerst `read_file` auf die betroffene Datei + `application.properties`.
- Neue Features immer in neuer Klasse (nie bestehende Klasse > 300 Zeilen aufblasen).
- Immer zuerst Planning Mode + Task-List verlangen.
- Nach Änderung: `quarkus:dev` und `native:compile` im Hinterkopf behalten (keine Breaking Changes für Native).

**Diese Rule hat höchste Priorität nach modular-architecture.**
Bei Konflikt mit anderen Rules gilt immer: Quarkus-Best-Practices zuerst.
