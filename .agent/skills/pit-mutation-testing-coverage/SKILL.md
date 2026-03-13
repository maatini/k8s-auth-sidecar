---
name: pit-mutation-testing-coverage
description: PIT Mutation Testing konfigurieren und interpretieren (POJO-Strategie)
---

**Wann verwenden?**  
- Bei Änderungen an Kernlogik (Services, Utils, Models)  
- Vor jedem Release oder großen Refactoring  
- Wenn Mutation Score unter Zielwert fällt  

**Gemessene Werte (2026-03-06, POJO+ExtTests)**
- `auth-core` (application.service): Line **91%** | Mutation **77%** | Test Strength **80%** ✅
- `auth-core` (domain.model): Line **38%** | Mutation **23%** | Test Strength **79%** 🕑
- `opa-wasm` (PolicyService): Line **100%** | Mutation **91%** | Test Strength **91%** ✅
- `opa-wasm` (WasmEngine): Line **56%** | Mutation **35%** | Test Strength **54%** 🕑
- `config` (Health): Line **60%** | Mutation **26%** | Test Strength **71%** 🕑

> 🕑 Niedrige Scores bei Domain-Models und Engine-Code sind **gewollt** (CDI-Boilerplate, Quarkus-spezifisch).  
> Zielwert für reine Services (application.service): **Test Strength >75%**

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
