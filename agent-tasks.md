# 🚀 agent-tasks.md – Dein Antigravity Task-Center

Dieses File ist der zentrale Einstiegspunkt für **autonome KI-Agents** (Google Antigravity, Cursor, Claude Code, etc.).  
Hier findest du fertige Prompt-Templates, mit denen du **komplette Use-Cases** in einem Shot implementieren kannst.

**Aktueller Stand (13. März 2026):**  
- 5 sofort einsatzbereite Templates für neue Use-Cases  
- Alle Templates berücksichtigen die POJO-First-Test-Philosophie (JUNIOR_GUIDE.md)  
- Ziel: 100 % Agent-ready in unter 10 Minuten pro Use-Case

## 📋 Die 5 fertigen Prompt-Templates

### Template 1: Neuer JWT-Validation-UseCase
```
Du bist Senior Quarkus-Architect. Implementiere einen neuen UseCase im Modul auth-core:

- Package: de.edeka.eit.k8sauth.usecase.jwt
- Klasse: JwtValidationUseCase.java (POJO, @ApplicationScoped)
- Methode: Uni<ValidationResult> execute(Uni<JwtValidationCommand> command)
- Nutze bestehende Klassen: JwtParser, TokenValidator, CaffeineCache
- Vollständige POJO-Unit-Tests (mit PIT-kompatiblen Mutanten) + TokenTestDataFactory
- Integriere in SidecarRequestProcessor.java (neu aufrufen statt direktem Aufruf)
- Füge JavaDoc + einzeiligen Header-Kommentar hinzu
- Aktualisiere ARCHITECTURE.md und agent-guide.md

Plane, implementiere, teste und committe alles in einem Durchgang.
```

### Template 2: Neuer AuthorizationUseCase mit OPA-WASM
```
Du bist Senior Quarkus-Architect. Implementiere einen neuen UseCase im Modul auth-core:

- Package: de.edeka.eit.k8sauth.usecase.authorization
- Klasse: AuthorizationUseCase.java (POJO)
- Methode: Uni<AuthorizationResult> execute(Uni<AuthorizationCommand> command)
- Verwende WasmPolicyEngine + PolicyService
- Hot-Reload-fähig via ConfigMap
- Vollständige POJO-Tests + opa test Integration
- Erweitere SidecarRouteHandler.java um neuen Endpoint /authorize
- OpenAPI-Beispiel-Request in docs/api-spec.md hinzufügen

Plane, implementiere, teste und committe alles in einem Durchgang.
```

### Template 3: Vault-Secrets-Provider-UseCase
```
Du bist Senior Quarkus-Architect. Implementiere einen neuen UseCase im Modul auth-core:

- Package: de.edeka.eit.k8sauth.usecase.secrets
- Klasse: VaultSecretsUseCase.java (POJO)
- Methode: Uni<SecretResult> execute(Uni<VaultCommand> command)
- Nutze quarkus-vault Extension (falls nötig hinzufügen)
- Caching mit Caffeine
- Vollständige POJO-Tests + Integrationstest mit WireMock
- Integriere in SidecarRequestProcessor für secret-enriched Tokens

Plane, implementiere, teste und committe alles in einem Durchgang.
```

### Template 4: Custom Role-Mapping-UseCase (Entra ID / Keycloak)
```
Du bist Senior Quarkus-Architect. Implementiere einen neuen UseCase im Modul auth-core:

- Package: de.edeka.eit.k8sauth.usecase.roles
- Klasse: RoleMappingUseCase.java (POJO)
- Methode: Uni<RoleMappingResult> execute(Uni<RoleMappingCommand> command)
- Unterstützt Entra ID + Keycloak Claims
- Erweitere RolesService.java
- Vollständige POJO-Tests + PIT

Plane, implementiere, teste und committe alles in einem Durchgang.
```

### Template 5: Cache-Invalidation-UseCase
```
Du bist Senior Quarkus-Architect. Implementiere einen neuen UseCase im Modul auth-core:

- Package: de.edeka.eit.k8sauth.usecase.cache
- Klasse: CacheInvalidationUseCase.java (POJO)
- Methode: Uni<Void> execute(Uni<InvalidationCommand> command)
- Nutze Caffeine + EventBus (Mutiny)
- Vollständige POJO-Tests
- Triggerbar über ConfigMap-Change

Plane, implementiere, teste und committe alles in einem Durchgang.
```

**Nächste Schritte für Agents:**  
1. Wähle ein Template  
2. Füge es in `SidecarRequestProcessor.java` ein  
3. Starte `./mvnw quarkus:dev`  
4. Aktualisiere `docs/agent-guide.md` und `ARCHITECTURE.md`

Viel Spaß beim autonomen Entwickeln! 🚀
