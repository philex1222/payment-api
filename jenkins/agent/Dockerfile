# =============================================================================
# Jenkins Build Agent — payment-api
#
# Includes everything the Jenkinsfile needs:
#   - Eclipse Temurin 21 JDK (matches the jenkins/jenkins:latest controller)
#   - Apache Maven 3.9
#   - Docker CLI (talks to the host socket mounted at runtime)
#   - kubectl (for Kubernetes deployments)
#   - Helm 3 (for chart upgrades)
#   - Trivy (for container + filesystem security scans)
#
# Build:
#   docker build -t payment-api-jenkins-agent:latest jenkins/agent/
#
# Run as a Jenkins inbound agent (replace <SECRET> and <JENKINS_URL>):
#   docker run -d \
#     --name payment-api-agent \
#     -v /var/run/docker.sock:/var/run/docker.sock \
#     -e JENKINS_URL=http://<JENKINS_URL>:8080 \
#     -e JENKINS_AGENT_NAME=payment-api-agent \
#     -e JENKINS_SECRET=<SECRET> \
#     payment-api-jenkins-agent:latest
# =============================================================================

# Java 21 required — jenkins/jenkins:latest controller compiles class files at
# version 65 (Java 21) which a Java 17 agent cannot load.
# Maven will still compile the Spring Boot app with --release 17 per pom.xml.
FROM eclipse-temurin:21-jdk-jammy

ARG MAVEN_VERSION=3.9.9
ARG HELM_VERSION=3.16.3
ARG TRIVY_VERSION=0.69.3
ARG KUBECTL_VERSION=v1.31.0
ARG JENKINS_AGENT_VERSION=3256.v88a_f6e922152

# Note: JAVA_HOME in this image is /opt/java/openjdk (Java 21).
# The Spring Boot app compiles with --release 17 via pom.xml settings.

LABEL maintainer="payment-api team" \
      description="Jenkins build agent for payment-api" \
      org.opencontainers.image.source="https://github.com/philex1222/payment-api"

# ── System packages ────────────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        git \
        gnupg \
        unzip \
        wget \
        jq \
        fontconfig \
    && rm -rf /var/lib/apt/lists/*

# ── Docker CLI (client only — daemon runs on the host) ────────────────────────
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
       | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && chmod a+r /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
       https://download.docker.com/linux/ubuntu \
       $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli \
    && rm -rf /var/lib/apt/lists/*

# ── Apache Maven ──────────────────────────────────────────────────────────────
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# ── kubectl ───────────────────────────────────────────────────────────────────
RUN curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
        -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl \
    && kubectl version --client

# ── Helm 3 ────────────────────────────────────────────────────────────────────
RUN curl -fsSL "https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz" \
    | tar -xz -C /tmp \
    && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && rm -rf /tmp/linux-amd64 \
    && helm version

# ── Trivy ─────────────────────────────────────────────────────────────────────
RUN curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz" \
    | tar -xz -C /tmp \
    && mv /tmp/trivy /usr/local/bin/trivy \
    && trivy --version

# ── Jenkins inbound agent JAR ─────────────────────────────────────────────────
# The agent.jar connects this container to the Jenkins controller.
RUN mkdir -p /usr/share/jenkins \
    && curl -fsSL \
       "https://repo.jenkins-ci.org/releases/org/jenkins-ci/main/remoting/${JENKINS_AGENT_VERSION}/remoting-${JENKINS_AGENT_VERSION}.jar" \
       -o /usr/share/jenkins/agent.jar \
    && chmod 755 /usr/share/jenkins/agent.jar

# ── Non-root user (matches Helm chart's runAsUser: 1000) ─────────────────────
RUN groupadd -g 1000 jenkins \
    && useradd  -u 1000 -g jenkins -m -s /bin/bash jenkins \
    # Add jenkins user to the docker group so it can talk to the host socket
    && groupadd -g 999 docker 2>/dev/null || true \
    && usermod  -aG docker jenkins

# ── Maven local repository cache directory ────────────────────────────────────
# Mount a named volume here to persist the Maven cache between builds.
RUN mkdir -p /home/jenkins/.m2 \
    && chown -R jenkins:jenkins /home/jenkins

# ── Auto-configuring entrypoint ───────────────────────────────────────────────
# Fetches the JNLP secret from the controller if JENKINS_SECRET is not set,
# removing the manual "copy the token" step from first-run setup.
COPY --chown=jenkins:jenkins entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER jenkins
WORKDIR /home/jenkins

ENV JAVA_OPTS="-Xmx1024m"

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
