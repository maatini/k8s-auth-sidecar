---
name: multi-tenant-oidc-keycloak-entra
description: Multi-Tenant OIDC mit Keycloak und Microsoft Entra ID konfigurieren (JWKS, Issuer, Claims)
---

**Wann verwenden?**  
- Bei Keycloak + Entra ID gleichzeitig  
- Multi-Tenant-Setup (verschiedene Realms/Tenants)  
- WireMock-Tests mit "issuer: any"  

**Keycloak-Config (application.yaml)**
```yaml
quarkus.oidc.auth-server-url: ${OIDC_AUTH_SERVER_URL}
quarkus.oidc.token.issuer: any          # für WireMock wichtig!
quarkus.oidc.jwks.resolve-early: false
```

**Entra ID (zusätzlich)**
```yaml
sidecar.auth.entra.enabled: true
sidecar.auth.entra.tenant-id: ${ENTRA_TENANT_ID}
```

**Pitfalls**
- Immer `issuer: any` bei Mock-Token  
- `groups` vs. `realm_access.roles` unterscheiden  
- Multi-Tenant: Tenant-Claim in Policy abfragen  

**Verwandte Skills**  
- opa-authz-engineer-for-sidecar
