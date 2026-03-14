---
name: metrics-observability-opentelemetry
description: Prometheus-Metriken + OpenTelemetry Tracing konfigurieren und erweitern
---

**Wann verwenden?**  
- Bei Änderungen an Metriken oder Observability  
- Beim Einrichten von Monitoring / Grafana / Prometheus  
- Wenn OTEL-Tracing aktiviert werden soll  

**Wichtige Metriken**
- `sidecar_ext_authz_requests_total`
- `sidecar_ext_authz_errors_total`
- `sidecar_auth_success_total` / `sidecar_auth_failure_total`
- `sidecar_authz_allow_total` / `sidecar_authz_deny_total`
- `sidecar_ext_authz_latency_seconds` (Histogram)

**Konfiguration (application.yaml)**
```yaml
quarkus:
  micrometer:
    export:
      prometheus:
        enabled: true
  otel:
    enabled: ${OTEL_ENABLED:false}
    exporter:
      otlp:
        endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}
```

**Endpoints**
- Metrics: `/q/metrics`
- Health: `/q/health/live` und `/q/health/ready`

**Grafana / Prometheus Setup**
- Scrape Job auf `/q/metrics`
- In Produktion OTEL Collector verwenden

**Pitfalls**
- In `%dev` Profil ist OTEL normalerweise deaktiviert
- In Produktion immer `OTEL_ENABLED=true` setzen
- Hohe Cardinality bei Labels vermeiden

**Verwandte Skills**
- k8s-auth-sidecar-dev-workflow
- security-hardening-trivy-cve
