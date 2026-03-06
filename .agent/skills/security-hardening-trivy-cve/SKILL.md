---
name: security-hardening-trivy-cve
description: CVE-Scan, non-root User, SBOM, SecurityContext und Secrets
---

**Wann verwenden?**  
- Bei jedem Docker-Build oder Release  
- Wenn neue Abhängigkeiten oder Base-Images hinzugefügt werden  
- Bei Security-Reviews oder Compliance-Anforderungen  

**CI-Scan (in .github/workflows/ci.yml + release.yml)**
```yaml
- name: Trivy FS Scan
  uses: aquasecurity/trivy-action@master
  with:
    scan-type: 'fs'
    scan-ref: '.'
    severity: 'CRITICAL,HIGH'
    exit-code: '1'

- name: Trivy Image Scan
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: 'k8s-auth-sidecar:latest'
    format: 'table'
    exit-code: '1'
    severity: 'CRITICAL,HIGH'
    scanners: 'vuln,secret,misconfig'
```

**Docker Best Practices (Dockerfile + Dockerfile.native)**
- Non-root User: `USER 1001` (oder `sidecar`)
- `readOnlyRootFilesystem: true` im SecurityContext
- `allowPrivilegeEscalation: false`
- `capabilities.drop: ["ALL"]`
- CVE-Patch: `apk upgrade --no-cache` (Alpine) bzw. `microdnf update`
- CycloneDX SBOM: `mvn cyclonedx:makeAggregateBom`

**Kubernetes SecurityContext (k8s/base/deployment.yaml)**
```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1001
  fsGroup: 1000
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
```

**Pitfalls**
- Vergiss nicht, OPA-CLI und Policies im Image zu mounten (Hot-Reload!)
- Secrets **nie** im Image, immer über K8s Secrets
- Trivy-Scan muss `exit-code: '1'` haben, sonst ignoriert CI kritische CVEs

**Verwandte Skills**  
- native-image-build-optimization  
- k8s-auth-sidecar-kustomize-deployment  
- rate-limiting-anti-spoofing
