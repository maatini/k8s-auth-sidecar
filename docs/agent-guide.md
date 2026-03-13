# 🤖 docs/agent-guide.md – Antigravity Agent Guide

**Willkommen, autonomer Agent!**  
Dieses Dokument ist dein offizieller Leitfaden, wie du im **k8s-auth-sidecar** Repository mit minimalen Prompts komplette Features baust.

**Ziel:** Du kannst mit einem einzigen Prompt einen neuen UseCase von 0 bis produktiv fertigstellen – inklusive Tests, Dokumentation und OpenAPI.

## 📍 Repository-Struktur (Agent-View)

```
root/
├── .agent/              ← deine Regeln & Skills
├── auth-core/           ← alle UseCases + Domain + POJO-Tests
├── proxy/               ← REST-Resources + Proxy-Pipeline
├── docs/
│   ├── agent-guide.md   ← du bist hier
│   ├── ARCHITECTURE.md
│   └── api-spec.md
├── agent-tasks.md       ← 5 fertige Prompt-Templates
└── JUNIOR_GUIDE.md      ← POJO-Test-Philosophie
```

## ✅ Agent-First Workflow (5 Minuten)

1. Lies `agent-tasks.md` → wähle Template  
2. Kopiere den Prompt in dein Agent-Tool  
3. Agent erstellt: UseCase + Tests + Integration in Proxy  
4. Starte `docker compose -f docker-compose.dev.yml up -d`  
5. Teste mit `./mvnw quarkus:dev` + Swagger  
6. Commit & PR

## 📌 Wichtige Regeln (niemals verletzen!)

- **Immer POJO-First**: Kein `@QuarkusTest` für Kernlogik (siehe JUNIOR_GUIDE.md)
- **Clean Architecture**: UseCase → Service → Repository
- **Mutiny überall**: `Uni<...>` statt Blocking
- **AuthContext in REST**: In REST-Endpoints (wie `ProxyResource`) die Auth-Informationen über `containerRequestContext.getProperty("auth.context")` extrahieren (gesetzt durch `AuthProxyFilter`).
- **Dokumentation**: Jede Klasse bekommt einzeiligen Header + JavaDoc
- **OpenAPI**: Nach jedem neuen Endpoint `docs/api-spec.md` aktualisieren
- **PIT-Testing**: Neue Tests müssen PIT-Strength > 80 % halten

## 🔥 Die 5 fertigen Prompt-Templates

(Die vollständigen Templates stehen in `../agent-tasks.md` – einfach dorthin springen!)

## 📈 Nächste Schritte für dich (Agent)

- Erstelle neue UseCases mit den Templates oben  
- Erweitere dieses Guide, wenn du neue Patterns entdeckst  
- Halte Coverage > 85 % und PIT-Strength > 80 %

Du bist jetzt offiziell **Antigravity-ready**!  

Viel Erfolg beim Bau des nächsten Zero-Trust-Sidecars! 🛡️

— Dein Senior Quarkus-Architect & Google Antigravity Backend-Reviewer
