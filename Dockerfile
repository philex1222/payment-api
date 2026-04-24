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
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy POM first so dependency layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline --batch-mode --no-transfer-progress -q

COPY src ./src
RUN mvn package --batch-mode --no-transfer-progress -DskipTests -q

# ── Stage 2: Extract layers ───────────────────────────────────────────────────
# Using jammy (Ubuntu 22.04) — ships OpenSSL 3.0.x which is NOT affected by the
# OpenSSL 3.5/3.6 CVEs present in Alpine 3.22's OpenSSL 3.5.x package.
FROM eclipse-temurin:21-jre-jammy AS layers
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Spring Boot 3's layertools extracts the JAR into well-separated directories
RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 3: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# CI/CD passes a per-run value so the security patch layer is refreshed even
# when Docker's remote cache can reuse the rest of the image build.
ARG SECURITY_PATCH_EPOCH=local

# Upgrade all packages to apply latest security patches before locking down to
# a non-root user. Also installs wget for the HEALTHCHECK (not pre-installed in
# the jammy JRE image unlike Alpine).
RUN echo "Refreshing OS security patches: ${SECURITY_PATCH_EPOCH}" \
    && apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

# Create a dedicated non-root user; UID 1000 matches the Helm podSecurityContext
RUN groupadd -g 1000 appuser \
    && useradd -r -u 1000 -g appuser appuser

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

# Health check via Spring Boot Actuator (wget is installed above for Jammy)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM flags: use container memory limits, heap dump to writable /tmp
ENV JAVA_OPTS="\
  -XX:MaxRAMPercentage=75 \
  -XX:InitialRAMPercentage=50 \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heap-dump.hprof \
  -Djava.security.egd=file:/dev/./urandom"

# Launch via the Spring Boot layered launcher (not the fat JAR directly).
# 'exec' replaces the shell with the JVM process, making Java PID 1 so that
# SIGTERM from 'docker stop' / Kubernetes pod termination is forwarded directly
# to the JVM and Spring Boot can perform a graceful shutdown.
ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
