---
name: k8s-auth-sidecar-kustomize-deployment
description: Kustomize-Overlays, Deployment, Service-Routing und ConfigMap für Policies erstellen
---

**Wann verwenden?**  
- Beim Hinzufügen des Sidecars zu einem bestehenden Deployment  
- Für dev / prod Overlays  
- Bei Policy- oder Secret-Änderungen  

**Schritt-für-Schritt**
1. `k8s/base/deployment.yaml` anpassen (Sidecar-Container + OIDC-Konfiguration)  
2. `k8s/overlays/development/kustomization.yaml` patchen  
3. Policies in `k8s/base/config.yaml` (ConfigMap `k8s-auth-sidecar-policies`)  
4. `kubectl apply -k k8s/overlays/development`  

**Wichtige Env-Variablen (immer setzen)**
- `OIDC_AUTH_SERVER_URL=https://keycloak.example.com/realms/myrealm`  
- `OIDC_CLIENT_ID=k8s-auth-sidecar`  
- `RATE_LIMIT_TRUSTED_PROXIES=10.244.0.1,...` (Ingress-IPs!)  

**Pitfalls**
- Service **muss** auf Port 8080 (Sidecar) zeigen  
- Policies werden als ConfigMap gemountet → Hot-Reload funktioniert  
- Secrets immer über `k8s-auth-sidecar-secrets`  

**Verwandte Skills**  
- security-hardening-trivy-cve  
- native-image-build-optimization

