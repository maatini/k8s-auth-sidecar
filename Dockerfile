# =====================================================
# RR-Sidecar Multi-Stage Dockerfile
# =====================================================
# Stage 1: Build with Maven
# Stage 2: Native Image Build (optional)
# Stage 3: Runtime Image
# =====================================================

# =====================================================
# Stage 1: Build
# =====================================================
FROM eclipse-temurin:21-jdk-alpine AS build

LABEL maintainer="space.maatini"
LABEL description="RR-Sidecar AuthN/AuthZ Microservice Build Stage"

# Install Maven
RUN apk add --no-cache maven

WORKDIR /app

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Download dependencies (cached if pom.xml unchanged)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn package -DskipTests -Dquarkus.package.type=uber-jar

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
#     -o /app/rr-sidecar

# =====================================================
# Stage 3: Runtime (JVM Mode)
# =====================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="space.maatini"
LABEL description="RR-Sidecar AuthN/AuthZ Microservice"
LABEL version="1.0.0"

# Create non-root user for security
RUN addgroup -S sidecar && adduser -S sidecar -G sidecar

WORKDIR /app

# Copy the uber-jar from build stage
COPY --from=build /app/target/*-runner.jar /app/rr-sidecar.jar

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
    OPA_ENABLED=true \
    OPA_MODE=embedded \
    OPA_POLICY_DIR=/policies

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/rr-sidecar.jar"]

# =====================================================
# Stage 3 Alternative: Native Runtime
# =====================================================
# FROM gcr.io/distroless/static-debian12 AS native-runtime
# 
# COPY --from=native-build /app/rr-sidecar /app/rr-sidecar
# COPY --from=build /app/src/main/resources/policies /policies
# 
# EXPOSE 8080
# 
# ENTRYPOINT ["/app/rr-sidecar"]
