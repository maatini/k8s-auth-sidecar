---
name: rate-limiting-anti-spoofing
description: Rate-Limiting mit Caffeine + X-Forwarded-For Schutz vor IP-Spoofing
---

**Wann verwenden?**  
- Bei Änderungen am Rate-Limiter  
- Wenn IP-Spoofing-Schutz konfiguriert werden soll  
- Bei Performance-Optimierungen des Caches  

**Konfiguration (application.yaml / Env)**
```yaml
sidecar:
  rate-limit:
    enabled: ${RATE_LIMIT_ENABLED:true}
    requests-per-second: ${RATE_LIMIT_RPS:100}
    burst: ${RATE_LIMIT_BURST:200}
    trusted-proxies: ${RATE_LIMIT_TRUSTED_PROXIES:10.244.0.1,10.244.0.2}
```

**Wichtige Features**
- Caffeine-Cache (zeit- und größenlimitiert)  
- X-Forwarded-For / X-Real-IP nur von `trusted-proxies` akzeptiert  
- Automatischer Fallback auf Remote-Address bei fehlendem Header  

**Pitfalls**
- `trusted-proxies` **muss** die IPs des Ingress/Loadbalancers enthalten – sonst wird jeder Request als Spoofing gewertet!  
- Nie eine unbegrenzte `ConcurrentHashMap` verwenden (Memory-Leak-Gefahr)  
- In `%dev` Profil meist deaktiviert  

**Verwandte Skills**  
- security-hardening-trivy-cve  
- quarkus-sidecar-proxy-pattern
