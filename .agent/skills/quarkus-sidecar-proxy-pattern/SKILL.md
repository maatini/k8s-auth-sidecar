---
name: quarkus-sidecar-proxy-pattern
description: Streaming Proxy, Auth-Header-Anreicherung und non-blocking Pipeline (Vert.x + Mutiny)
---

**Wann verwenden?**  
- Bei Änderungen an `ProxyService.java`  
- Streaming-Body (POST/PUT/PATCH)  
- Custom Header mit `${user.id}`  

**Wichtige Methoden**
- `proxy(...)` → send() oder sendStream() je nach Methode  
- `resolveAuthContextHeaders()` → `${user.id}` Platzhalter  
- `resolvePropagatedHeaders()` → X-Forwarded-*  

**Beispiel: Custom Header**
```yaml
sidecar.proxy.add-headers:
  X-User-ID: "${user.id}"
  X-Roles: "${user.roles}"
```

**Pitfalls**
- GET mit Body → immer `send()` (nicht sendStream)  
- Content-Length/Transfer-Encoding nicht weiterleiten  
- AuthContext aus `containerRequestContext.getProperty("auth.context")` lesen  

**Verwandte Skills**  
- rate-limiting-anti-spoofing
