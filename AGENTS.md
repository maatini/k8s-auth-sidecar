# 🤖 AGENTS.md – Official Google Antigravity Agent Brain

**Willkommen, autonomer Agent!**  
Dies ist die **zentral entdeckte** Datei für Google Antigravity, Cursor, Claude Code, GitHub Copilot Workspace & Co.  
Jeder Agent liest diese Datei **automatisch** beim Workspace-Scan und folgt den hier definierten Regeln.

**Ziel:** Du kannst mit einem einzigen Prompt komplette Use-Cases bauen – von 0 bis produktiv – und das Repo bleibt 100 % agent-ready.

## 📍 Wichtige Agent-Dateien (automatisch referenziert)

- **agent-tasks.md** → 5 fertige Prompt-Templates für neue Use-Cases
- **docs/agent-guide.md** → Dein detaillierter Agent-Leitfaden
- **.agent/rules/** + **.agent/skills/** → Agent-spezifische Regeln & Skills
- **JUNIOR_GUIDE.md** → POJO-First-Test-Philosophie + PIT-Regeln
- **docs/ARCHITECTURE.md** → C4/Mermaid-Architektur (Mermaid-C4)
- **docs/api-spec.md** → OpenAPI-Beispiele (nach jedem neuen Endpoint aktualisieren)

## ✅ Agent-First Workflow (4 Schritte)

1. Lies diese Datei + `agent-tasks.md`
2. Wähle ein Template oder beschreibe deinen Use-Case
3. Implementiere: UseCase → Service → Repository (Clean Architecture)
4. Teste mit POJO-Tests + PIT → Coverage > 85 % + PIT-Strength > 80 %
5. Aktualisiere Swagger (`/q/openapi`), ARCHITECTURE.md und dieses File

## 📌 Strenge Regeln (niemals verletzen!)

- **Immer POJO-First**: Kernlogik = reine Java-Klassen (kein `@QuarkusTest` für Domain/UseCase)
- **Mutiny überall**: `Uni<T>` / `Multi<T>` – kein Blocking
- **Clean Layers**: `proxy/` (Resources) → `auth-core/usecase/` → Service → Repository
- **Dokumentation**: Jede neue Klasse bekommt einzeiligen Header + JavaDoc + `@Description`
- **OpenAPI**: Nach jedem neuen Endpoint `docs/api-spec.md` + smallrye-openapi aktualisieren
- **Tests**: PIT-Mutation-Testing muss > 80 % bleiben (Plugin ist bereits im Root-POM)
- **Quarkus-Version**: Bleibe bei 3.32+ (aktuell 3.32.3)
- **Event-Loop-Schutz**: CPU-intensive Tasks (WASM-Evaluierung, JSON-Parsing) **niemals** auf dem Vert.x Event Loop ausführen. Immer `@Blocking` oder `Uni.emitOn(Infrastructure.getDefaultWorkerPool())` verwenden. Blockierende Calls auf dem Event Loop sind ein kritisches Anti-Pattern und limitieren den Throughput hart auf < 1000 RPS.

## 🔥 Schnellstart-Prompt für Agents

```
Du bist Senior Quarkus-Architect und Google Antigravity Backend-Reviewer.
Lies zuerst AGENTS.md, agent-tasks.md und docs/agent-guide.md.
Implementiere den UseCase aus Template X (oder beschreibe ihn selbst).
Respektiere POJO-First, Clean Architecture und alle Regeln.
Plane, implementiere, teste, dokumentiere und committe in einem Durchgang.
```

**Du bist jetzt vollautomatisch agent-ready.**  
Google Antigravity wird diese Datei priorisieren und alle anderen automatisch einbinden.

Viel Erfolg beim Bau des nächsten Zero-Trust-k8s-Sidecars! 🛡️🚀

— Dein Senior Quarkus-Architect & offizieller Google Antigravity Backend-Reviewer
