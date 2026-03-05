---
name: pit-mutation-testing-coverage
description: PIT Mutation Testing konfigurieren und interpretieren (POJO-Strategie)
---

**Wann verwenden?**  
- Bei Änderungen an Kernlogik (Services, Utils, Models)  
- Vor jedem Release oder großen Refactoring  
- Wenn Mutation Score unter Zielwert fällt  

**Zielwerte (2026-03-05)**
- Kernservices (`AuthenticationService`, `ProxyService`, `PathMatcher`, `PolicyService`): **>85 %**  
- Filter & Engine (`AuthProxyFilter`, `WasmPolicyEngine`, `AuditLogFilter`): **60–75 %** (Quarkus-spezifisch)  
- Gesamtprojekt: **>80 %** bei POJO-Tests  

**Befehl**
```bash
# Vollständiger Report
mvn pitest:mutationCoverage

# Nur POJO-Tests (schnell & empfohlen)
mvn pitest:mutationCoverage -DtargetTests=space.maatini.sidecar.*PojoTest,space.maatini.sidecar.*ExtTest
```

**PIT-Konfiguration (pom.xml)**
- Mutator: `STRONGER`
- Target: nur `space.maatini.sidecar.*`
- Excluded: `*IT`, `*E2ETest`
- Plugin: `pitest-maven` + `pitest-junit5-plugin`

**Warum POJO-Tests?**
- Quarkus-Integrationstests stören PIT (Classloader, CDI, Mocking)
- POJO-Tests sind blitzschnell und liefern höchste Abdeckung
- Kombination mit JaCoCo Branch Coverage >80 % = maximale Sicherheit

**Pitfalls**
- Nie `*Test` ohne POJO-Präfix in PIT laufen lassen → viele „SURVIVED“ Mutanten
- Nach Refactoring immer `mvn clean` vor PIT
- Niedriger Score bei Filtern ist **gewollt** und kein Bug

**Verwandte Skills**  
- k8s-auth-sidecar-dev-workflow  
- policy-testing-validation
