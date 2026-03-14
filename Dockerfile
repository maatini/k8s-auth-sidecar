# =====================================================
# K8s-Auth-Sidecar Multi-Stage Dockerfile (Multi-Module)
# =====================================================
FROM eclipse-temurin:25-jdk-alpine AS build

LABEL maintainer="space.maatini"
LABEL description="K8s-Auth-Sidecar AuthN/AuthZ Microservice Build Stage"

# Install Maven
RUN apk add --no-cache maven

WORKDIR /app

# Copy poms first for better layer caching
COPY pom.xml .
COPY auth-core/pom.xml auth-core/
COPY proxy/pom.xml proxy/
COPY opa-wasm/pom.xml opa-wasm/
COPY config/pom.xml config/

# Download dependencies (use BuildKit cache mount for speed)
RUN mvn dependency:go-offline -B

# Copy source code for all modules
COPY auth-core/src auth-core/src
COPY proxy/src proxy/src
COPY opa-wasm/src opa-wasm/src
COPY config/src config/src

# Build the proxy application (which includes other modules)
RUN mvn package -DskipTests -Dquarkus.package.type=uber-jar -pl proxy -am

# =====================================================
# Stage 3: Runtime (JVM Mode)
# =====================================================
FROM eclipse-temurin:25-jre-alpine AS runtime

# Build Arguments
ARG VERSION=0.3.0

# OCI standard labels
LABEL org.opencontainers.image.title="K8s-Auth-Sidecar"
LABEL org.opencontainers.image.description="AuthN/AuthZ Microservice"
LABEL org.opencontainers.image.authors="space.maatini"
LABEL org.opencontainers.image.version="${VERSION}"
LABEL org.opencontainers.image.source="https://github.com/maatini/k8s-auth-sidecar"

# Create non-root user for security
RUN addgroup -S sidecar && adduser -S sidecar -G sidecar

# CVE FIX – Patch Alpine packages
RUN apk upgrade --no-cache

WORKDIR /app

# Copy the uber-jar from build stage (located in proxy module target)
COPY --from=build /app/proxy/target/*-runner.jar /app/k8s-auth-sidecar.jar

# Copy policies
COPY --from=build /app/opa-wasm/src/main/resources/policies /policies

# Set ownership
RUN chown -R sidecar:sidecar /app /policies

# Switch to non-root user
USER sidecar

# Expose the sidecar port
EXPOSE 8080

# Health check (standard Quarkus path)
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/q/health/live || exit 1

# JVM options for containers
ENV JAVA_OPTS="-Xms128m -Xmx512m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom \
    -Dquarkus.http.host=0.0.0.0"

# Default environment variables
ENV QUARKUS_HTTP_PORT=8080 \
    QUARKUS_LOG_LEVEL=INFO \
    SIDECAR_LOG_LEVEL=DEBUG \
    PROXY_TARGET_HOST=localhost \
    PROXY_TARGET_PORT=8085 \
    AUTH_ENABLED=true \
    AUTHZ_ENABLED=true \
    OPA_ENABLED=true

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/k8s-auth-sidecar.jar"]
