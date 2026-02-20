echo '🚀 K8s-Auth-Sidecar Devbox Environment'
echo '☕ Java Version:' $(java -version 2>&1 | head -n 1)
echo '📦 Maven Version:' $(mvn -v | head -n 1)
echo '🛡️ OPA Version:' $(opa version | head -n 1)