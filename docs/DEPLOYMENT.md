# Deployment-Anleitung

Diese Anleitung beschreibt Schritt für Schritt, wie der RR-Sidecar in Kubernetes deployt wird.

## Voraussetzungen

- Kubernetes Cluster (1.24+)
- `kubectl` CLI
- `kustomize` (in kubectl integriert)
- Container Registry Zugang
- Identity Provider (Keycloak oder Entra ID)

## Schritt 1: Container-Image bauen und pushen

```bash
# 1. Image bauen
docker build -t your-registry.io/rr-sidecar:1.0.0 .

# 2. In Registry pushen
docker push your-registry.io/rr-sidecar:1.0.0
```

## Schritt 2: Konfiguration anpassen

### 2.1 Base-Konfiguration bearbeiten

Bearbeite `k8s/base/config.yaml`:

```yaml
# OIDC für Keycloak
OIDC_AUTH_SERVER_URL: "https://YOUR-KEYCLOAK/realms/YOUR-REALM"
OIDC_CLIENT_ID: "your-client-id"

# Optional: Entra ID
ENTRA_AUTH_SERVER_URL: "https://login.microsoftonline.com/YOUR-TENANT-ID/v2.0"
ENTRA_CLIENT_ID: "your-azure-client-id"

# Roles Service URL
ROLES_SERVICE_URL: "http://roles-service.namespace.svc.cluster.local:8080"
```

### 2.2 Secrets erstellen

```bash
kubectl create secret generic rr-sidecar-secrets \
  --from-literal=OIDC_CLIENT_SECRET='your-secret' \
  --from-literal=ENTRA_CLIENT_SECRET='your-azure-secret' \
  -n rr-sidecar-demo
```

### 2.3 Image-Tag anpassen

Bearbeite `k8s/overlays/production/kustomization.yaml`:

```yaml
images:
  - name: space.maatini/rr-sidecar
    newName: your-registry.io/rr-sidecar
    newTag: "1.0.0"
```

## Schritt 3: Policies konfigurieren

Bearbeite die Policy-ConfigMap in `k8s/base/config.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: rr-sidecar-policies
data:
  authz.rego: |
    package authz
    
    # Deine Policies hier...
```

## Schritt 4: Deployment anwenden

```bash
# Development
kubectl apply -k k8s/overlays/development

# Production
kubectl apply -k k8s/overlays/production
```

## Schritt 5: Verifizierung

```bash
# Pods prüfen
kubectl get pods -n rr-sidecar-demo

# Logs anzeigen
kubectl logs -n rr-sidecar-demo -l app=my-application -c rr-sidecar

# Health Check
kubectl exec -n rr-sidecar-demo deploy/my-application -c rr-sidecar -- \
  wget -qO- http://localhost:8080/q/health
```

## Schritt 6: Sidecar zu bestehender App hinzufügen

Um den Sidecar zu einer bestehenden Anwendung hinzuzufügen:

### 6.1 Deployment patchen

Erstelle ein Kustomize-Patch:

```yaml
# patch-sidecar.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: existing-app
spec:
  template:
    spec:
      containers:
        - name: rr-sidecar
          image: your-registry.io/rr-sidecar:1.0.0
          ports:
            - containerPort: 8080
          env:
            - name: PROXY_TARGET_HOST
              value: "localhost"
            - name: PROXY_TARGET_PORT
              value: "8080"  # Port deiner App
          envFrom:
            - configMapRef:
                name: rr-sidecar-config
            - secretRef:
                name: rr-sidecar-secrets
          volumeMounts:
            - name: policies
              mountPath: /policies
      volumes:
        - name: policies
          configMap:
            name: rr-sidecar-policies
```

### 6.2 Service anpassen

Der Service muss auf den Sidecar-Port zeigen:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: existing-app
spec:
  ports:
    - port: 80
      targetPort: 8080  # Sidecar-Port, NICHT App-Port!
```

## Troubleshooting

### Problem: 401 Unauthorized bei gültigem Token

1. OIDC-Konfiguration prüfen:
   ```bash
   kubectl logs -c rr-sidecar ... | grep -i oidc
   ```

2. JWKS-Endpoint erreichbar?
   ```bash
   kubectl exec ... -- wget -qO- $OIDC_AUTH_SERVER_URL/.well-known/openid-configuration
   ```

### Problem: 403 Forbidden trotz korrekter Rolle

1. Policy-Evaluation prüfen:
   ```bash
   kubectl logs -c rr-sidecar ... | grep -i policy
   ```

2. Roles-Service erreichbar?
   ```bash
   kubectl exec ... -- wget -qO- $ROLES_SERVICE_URL/health
   ```

### Problem: 502 Bad Gateway

1. Backend erreichbar?
   ```bash
   kubectl exec -c rr-sidecar ... -- wget -qO- http://localhost:$PROXY_TARGET_PORT/health
   ```

2. Backend-Port korrekt?

## Monitoring Setup

### Prometheus ServiceMonitor

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: rr-sidecar
spec:
  selector:
    matchLabels:
      app: my-application
  endpoints:
    - port: http
      path: /q/metrics
      interval: 30s
```

### Grafana Dashboard

Importiere das Dashboard aus `docs/grafana-dashboard.json` (falls vorhanden).

## Updates

### Rolling Update

```bash
# Image-Tag in Kustomization ändern
# Dann:
kubectl apply -k k8s/overlays/production
```

### Policy Update (ohne Restart)

Bei `OPA_MODE=embedded` und `watch-policies=true` werden Policies automatisch neu geladen:

```bash
kubectl patch configmap rr-sidecar-policies -n rr-sidecar-demo \
  --patch '{"data":{"authz.rego":"...neue policy..."}}'
```
