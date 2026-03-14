---
name: quarkus-sidecar-ext-authz-pattern
description: ext_authz Route Handler, Auth-Header-Anreicherung und non-blocking Pipeline (Vert.x + Mutiny)
---

**Wann verwenden?**  
- Bei Änderungen am `SidecarRouteHandler.java`  
- Custom Header Enrichment  
- Anpassungen am `/authorize` Endpunkt  

**Wichtige Klassen**
- `SidecarRouteHandler.authorize()` → Envoy ext_authz Endpoint  
- `HeaderSanitizer` → X-Forwarded-* Extraktion und Envoy-Header-Filterung  
- `RequestUtils` → Header und Query-Parameter Extraktion  

**Beispiel: Enrichment Header**
```yaml
# Response headers set by SidecarRouteHandler on 200 OK:
X-Auth-User-Id: "<subject from JWT>"
X-Enriched-Roles: "admin,editor"
```

**Pitfalls**
- AuthContext wird vom `SidecarRequestProcessor` aufgebaut  
- Envoy-interne Header (`x-envoy-*`) werden vor der Policy-Auswertung gefiltert  
- Response ist `200 OK` (allow) oder `403/401` (deny) — kein Body-Proxying  

**Verwandte Skills**  
- rate-limiting-anti-spoofing
