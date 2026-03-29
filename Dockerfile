# =============================================================================
# Multi-stage Dockerfile — Spring Boot Layered JAR
#
# Stage 1 (build): compile + package the application JAR.
# Stage 2 (layers): extract Spring Boot layers for optimal Docker cache reuse.
# Stage 3 (runtime): minimal JRE image running as a non-root user.
#
# Layer ordering (least-to-most frequently changed):
#   dependencies -> spring-boot-loader -> snapshot-dependencies -> application
# This means a code-only change only invalidates the 'application' layer.
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Copy POM first so dependency layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline --batch-mode --no-transfer-progress -q

COPY src ./src
RUN mvn package --batch-mode --no-transfer-progress -DskipTests -q

# ── Stage 2: Extract layers ───────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS layers
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Spring Boot 3's layertools extracts the JAR into well-separated directories
RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 3: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Install curl for the HEALTHCHECK below (minimal addition to slim image)
RUN apk add --no-cache curl

# Create a dedicated non-root user; UID 1000 matches the Helm podSecurityContext
RUN addgroup -S -g 1000 appuser && adduser -S -u 1000 -G appuser appuser

# Create writable log directory and set ownership before switching user
RUN mkdir -p /var/log/payment-api && chown -R appuser:appuser /var/log/payment-api

# Copy each Spring Boot layer individually — least-volatile layers first
# so Docker can reuse the cache for dependency/framework layers on code-only changes
COPY --from=layers --chown=appuser:appuser /app/dependencies          ./dependencies
COPY --from=layers --chown=appuser:appuser /app/spring-boot-loader    ./spring-boot-loader
COPY --from=layers --chown=appuser:appuser /app/snapshot-dependencies ./snapshot-dependencies
COPY --from=layers --chown=appuser:appuser /app/application           ./application

USER appuser

EXPOSE 8080

# Health check via Spring Boot Actuator (requires curl installed above)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

# JVM flags: use container memory limits, heap dump to writable /tmp
ENV JAVA_OPTS="\
  -XX:MaxRAMPercentage=75 \
  -XX:InitialRAMPercentage=50 \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heap-dump.hprof \
  -Djava.security.egd=file:/dev/./urandom"

# Launch via the Spring Boot layered launcher (not the fat JAR directly)
ENTRYPOINT ["sh", "-c", \
  "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
