#!/usr/bin/env bash
# =============================================================================
# Jenkins inbound agent — auto-configuring entrypoint
#
# If JENKINS_SECRET is already set, connects immediately.
# If not, polls the controller's Script Console to retrieve the JNLP secret
# for JENKINS_AGENT_NAME, then launches the agent JAR.
#
# Required env vars:
#   JENKINS_URL         e.g. http://jenkins-controller:8080
#   JENKINS_AGENT_NAME  e.g. payment-api-agent
#
# Optional env vars:
#   JENKINS_SECRET        pre-set secret (skips auto-fetch)
#   JENKINS_AGENT_WORKDIR work directory (default: /home/jenkins/workspace)
#   JENKINS_USER          controller admin user     (default: admin)
#   JENKINS_PASS          controller admin password (default: admin)
# =============================================================================
set -euo pipefail

JENKINS_URL="${JENKINS_URL:?JENKINS_URL must be set}"
AGENT_NAME="${JENKINS_AGENT_NAME:?JENKINS_AGENT_NAME must be set}"
WORK_DIR="${JENKINS_AGENT_WORKDIR:-/home/jenkins/workspace}"
JENKINS_USER="${JENKINS_USER:-admin}"
JENKINS_PASS="${JENKINS_PASS:-admin}"

SECRET="${JENKINS_SECRET:-}"

if [ -z "$SECRET" ]; then
    echo "==> JENKINS_SECRET not set — fetching automatically from ${JENKINS_URL}"

    GROOVY='println(jenkins.model.Jenkins.instance.getComputer("'"${AGENT_NAME}"'")?.getJnlpMac() ?: "")'
    MAX_WAIT=300
    WAITED=0
    INTERVAL=15

    COOKIE_JAR="/tmp/jenkins-cookies.txt"

    while [ "$WAITED" -lt "$MAX_WAIT" ]; do
        # Step 1: fetch crumb AND capture session cookie in one request.
        # Jenkins ties CSRF crumbs to the HTTP session, so both must be reused.
        CRUMB_JSON=$(curl -fsSL \
            -c "${COOKIE_JAR}" \
            -u "${JENKINS_USER}:${JENKINS_PASS}" \
            "${JENKINS_URL}/crumbIssuer/api/json" 2>/dev/null || echo "")
        CRUMB_FIELD=$(echo "${CRUMB_JSON}" | grep -o '"crumbRequestField":"[^"]*"' | cut -d'"' -f4 || echo "Jenkins-Crumb")
        CRUMB_VALUE=$(echo "${CRUMB_JSON}" | grep -o '"crumb":"[^"]*"' | cut -d'"' -f4 || echo "")

        # Step 2: POST to Script Console using the same session cookie + crumb
        RESPONSE=$(curl -fsSL \
            -X POST \
            -b "${COOKIE_JAR}" \
            -c "${COOKIE_JAR}" \
            -u "${JENKINS_USER}:${JENKINS_PASS}" \
            -H "${CRUMB_FIELD}: ${CRUMB_VALUE}" \
            --data-urlencode "script=${GROOVY}" \
            "${JENKINS_URL}/scriptText" 2>/dev/null \
            | tr -d '[:space:]' || true)

        # JNLP secret is exactly 64 hex characters
        if echo "${RESPONSE}" | grep -qE '^[0-9a-f]{64}$'; then
            SECRET="${RESPONSE}"
            echo "==> Secret obtained."
            break
        fi

        rm -f "${COOKIE_JAR}"
        echo "    Waiting for controller + node '${AGENT_NAME}' to be ready... (${WAITED}s elapsed)"
        sleep "${INTERVAL}"
        WAITED=$((WAITED + INTERVAL))
    done

    if [ -z "$SECRET" ]; then
        echo "ERROR: Could not retrieve JNLP secret for '${AGENT_NAME}' after ${MAX_WAIT}s."
        echo "       Check that Jenkins is up and the node is registered via JCasC."
        exit 1
    fi
fi

echo "==> Connecting agent '${AGENT_NAME}' to ${JENKINS_URL} (workDir: ${WORK_DIR})"
exec java ${JAVA_OPTS:-} \
    -jar /usr/share/jenkins/agent.jar \
    -url    "${JENKINS_URL}" \
    -name   "${AGENT_NAME}" \
    -secret "${SECRET}" \
    -workDir "${WORK_DIR}"
