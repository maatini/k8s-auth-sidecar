# =====================================================
# K8s-Auth-Sidecar Multi-Stage Dockerfile
# =====================================================
# Stage 1: Build with Maven
# Stage 2: Native Image Build (optional)
# Stage 3: Runtime Image
# =====================================================

# =====================================================
# Stage 1: Build
# =====================================================
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS build

LABEL maintainer="space.maatini"
LABEL description="K8s-Auth-Sidecar AuthN/AuthZ Microservice Build Stage"

# Install Maven
RUN apk add --no-cache maven

WORKDIR /app

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Download dependencies (use BuildKit cache mount for speed)
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application (use BuildKit cache mount for speed)
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -Dquarkus.package.type=uber-jar

# =====================================================
# Stage 2: Native Image Build (Optional)
# Uncomment to build native image
# =====================================================
# FROM ghcr.io/graalvm/native-image-community:21 AS native-build
# 
# WORKDIR /app
# 
# COPY --from=build /app/target/*-runner.jar /app/
# COPY --from=build /app/src/main/resources /app/resources
# 
# RUN native-image \
#     -jar /app/*-runner.jar \
#     -H:+ReportExceptionStackTraces \
#     --no-fallback \
#     --static \
#     -o /app/k8s-auth-sidecar

# =====================================================
# Stage 3: Runtime (JVM Mode)
# =====================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Build Arguments
ARG VERSION=0.2.0

# OCI standard labels
LABEL org.opencontainers.image.title="K8s-Auth-Sidecar"
LABEL org.opencontainers.image.description="AuthN/AuthZ Microservice"
LABEL org.opencontainers.image.authors="space.maatini"
LABEL org.opencontainers.image.version="${VERSION}"
LABEL org.opencontainers.image.source="https://github.com/maatini/k8s-auth-sidecar"

# Create non-root user for security
RUN addgroup -S sidecar && adduser -S sidecar -G sidecar

WORKDIR /app

# Copy the uber-jar from build stage
COPY --from=build /app/target/*-runner.jar /app/k8s-auth-sidecar.jar

# Copy policies
COPY --from=build /app/src/main/resources/policies /policies

# Set ownership
RUN chown -R sidecar:sidecar /app /policies

# Switch to non-root user
USER sidecar

# Expose the sidecar port
EXPOSE 8080

# Health check
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
    PROXY_TARGET_PORT=8081 \
    AUTH_ENABLED=true \
    AUTHZ_ENABLED=true \
    OPA_ENABLED=true

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/k8s-auth-sidecar.jar"]

# =====================================================
# Stage 3 Alternative: Native Runtime
# =====================================================
# FROM gcr.io/distroless/static-debian12 AS native-runtime
# 
# COPY --from=native-build /app/k8s-auth-sidecar /app/k8s-auth-sidecar
# COPY --from=build /app/src/main/resources/policies /policies
# 
# EXPOSE 8080
# 
# ENTRYPOINT ["/app/k8s-auth-sidecar"]
