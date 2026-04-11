#!/usr/bin/env bash
# =============================================================================
# smoke-test.sh — Payment API post-deployment health check
#
# Usage:
#   ./smoke-test.sh <namespace> [max-attempts] [sleep-seconds]
#
# Requires: KUBECONFIG env var to be set (done by the Jenkinsfile withCredentials block).
#
# Exit codes:
#   0 — health check passed (Spring Boot Actuator returned {"status":"UP"})
#   1 — all attempts exhausted without a passing health check
# =============================================================================

set -euo pipefail

NAMESPACE="${1:?Usage: smoke-test.sh <namespace>}"
MAX_ATTEMPTS="${2:-5}"
SLEEP_SECONDS="${3:-10}"
DEPLOYMENT="payment-api"
HEALTH_PATH="http://localhost:8080/actuator/health"

echo "==> Smoke test: ${DEPLOYMENT} in namespace ${NAMESPACE}"
echo "    Max attempts : ${MAX_ATTEMPTS}"
echo "    Retry delay  : ${SLEEP_SECONDS}s"

for attempt in $(seq 1 "${MAX_ATTEMPTS}"); do
    echo ""
    echo "--- Attempt ${attempt}/${MAX_ATTEMPTS} ---"

    # Check the pod is running before querying the health endpoint
    POD_READY=$(kubectl get deployment "${DEPLOYMENT}" \
        --namespace "${NAMESPACE}" \
        -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")

    if [ "${POD_READY:-0}" -lt 1 ]; then
        echo "    No ready replicas yet — waiting..."
        sleep "${SLEEP_SECONDS}"
        continue
    fi

    # Exec into the deployment pod and curl the Actuator health endpoint
    HEALTH_RESPONSE=$(kubectl exec \
        "deploy/${DEPLOYMENT}" \
        --namespace "${NAMESPACE}" \
        -- wget --no-verbose --tries=1 --spider --timeout=5 \
           "${HEALTH_PATH}" 2>&1 || true)

    # wget --spider returns 0 on HTTP 2xx, non-zero otherwise.
    # For a richer check, use wget -qO- and parse JSON:
    HEALTH_JSON=$(kubectl exec \
        "deploy/${DEPLOYMENT}" \
        --namespace "${NAMESPACE}" \
        -- wget -qO- --timeout=5 "${HEALTH_PATH}" 2>/dev/null || echo "")

    HEALTH_STATUS=$(echo "${HEALTH_JSON}" | grep -o '"status":"[A-Z]*"' | head -1 || echo "unknown")

    echo "    Response: ${HEALTH_STATUS:-no response}"

    if echo "${HEALTH_STATUS}" | grep -q '"status":"UP"'; then
        echo ""
        echo "==> Smoke test PASSED on attempt ${attempt}. Application is healthy."
        exit 0
    fi

    if [ "${attempt}" -lt "${MAX_ATTEMPTS}" ]; then
        echo "    Not healthy yet — retrying in ${SLEEP_SECONDS}s..."
        sleep "${SLEEP_SECONDS}"
    fi
done

echo ""
echo "==> Smoke test FAILED after ${MAX_ATTEMPTS} attempts."
echo "    Last health response: ${HEALTH_JSON:-<empty>}"
echo ""
echo "Diagnostics:"
kubectl get pods --namespace "${NAMESPACE}" -l "app.kubernetes.io/name=${DEPLOYMENT}" || true
kubectl describe deployment "${DEPLOYMENT}" --namespace "${NAMESPACE}" || true
exit 1
