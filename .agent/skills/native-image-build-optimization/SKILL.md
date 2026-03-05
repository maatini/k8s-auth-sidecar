---
name: native-image-build-optimization
description: GraalVM Native Image bauen, cachen und optimieren (Dockerfile.native + CI)
---

**Befehle**
```bash
docker build -f Dockerfile.native -t ... .
mvn package -Pnative -DskipTests   # lokal
```

**CI-Optimierung (release.yml)**
- Binary vorab bauen → in Docker-Cache mounten  
- `quarkus.native.additional-build-args=-H:+ReportExceptionStackTraces`  

**Pitfalls**
- OPA-CLI muss im Image sein (Hot-Reload)  
- `target/*-runner` richtig kopieren + chown 1001
