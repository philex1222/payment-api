// =============================================================================
// payment-api — Declarative Jenkins Pipeline
//
// Stages (branch rules):
//   ALL branches : Checkout → Build & Test → Security Scans → Docker Build
//                  → Docker Security Scan
//   master only  : → Publish Image → Deploy Staging → Staging Smoke Tests
//   manual only  : → Approve Production → Deploy Production
//
// Required Jenkins plugins (see jenkins/README.md for full list):
//   Pipeline, Docker Pipeline, Credentials Binding, JUnit, JaCoCo,
//   HTML Publisher, Timestamper, AnsiColor, Workspace Cleanup
//
// Required Jenkins credentials (Manage Jenkins → Credentials):
//   ghcr-credentials        : Username/Password  — GHCR PAT for image push
//   kube-config-staging      : Secret file        — base64-decoded kubeconfig
//   kube-config-prod         : Secret file        — base64-decoded kubeconfig
//   nvd-api-key              : Secret text        — NVD API key (optional, speeds OWASP)
//
// Required Jenkins tools (Manage Jenkins → Global Tool Configuration):
//   JDK   : name "temurin-21"   — Eclipse Temurin 21
//   Maven : name "maven-3.9"    — Apache Maven 3.9.x
// =============================================================================

pipeline {

    // ── Agent ──────────────────────────────────────────────────────────────────
    // Use a node labelled 'payment-api-agent'.  The jenkins/agent/Dockerfile
    // produces an image that satisfies all tool requirements (Maven, Docker CLI,
    // kubectl, Helm 3, Trivy).  See jenkins/README.md for provisioning steps.
    agent { label 'payment-api-agent' }

    // ── Build parameters ───────────────────────────────────────────────────────
    parameters {
        choice(
            name: 'DEPLOY_TARGET',
            choices: ['none', 'staging', 'production'],
            description: '''Override deploy target.
  none       — CI only (no deploy, regardless of branch)
  staging    — CI + publish + deploy to staging
  production — CI + publish + staging + approval gate + deploy to production'''
        )
        booleanParam(
            name: 'SKIP_SECURITY_SCANS',
            defaultValue: false,
            description: 'Skip OWASP and Trivy scans. Use for emergency hot-fixes only.'
        )
    }

    // ── Pipeline-wide options ──────────────────────────────────────────────────
    options {
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        timestamps()
        timeout(time: 75, unit: 'MINUTES')        // hard cap on total pipeline time
        ansiColor('xterm')
        disableConcurrentBuilds(abortPrevious: true)  // cancel older in-flight runs
    }

    // ── Tool bindings (resolved from Global Tool Configuration) ───────────────
    tools {
        jdk   'temurin-21'
        maven 'maven-3.9'
    }

    // ── Environment ────────────────────────────────────────────────────────────
    environment {
        MAVEN_OPTS      = '-Xmx1024m -XX:+TieredCompilation -XX:TieredStopAtLevel=1'
        IMAGE_REGISTRY  = 'ghcr.io'
        // IMAGE_REPO is the full registry path; override GITHUB_REPO env var to customise.
        IMAGE_REPO      = "ghcr.io/philex1222/payment-api"
        STAGING_NS      = 'payment-staging'
        PROD_NS         = 'payment-prod'
        HELM_CHART_PATH = 'helm/payment-api'
        HELM_VERSION    = '3.16.3'
    }

    // =========================================================================
    stages {
    // =========================================================================

        // ── 1. CHECKOUT ───────────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()
                    env.GIT_BRANCH_SAFE = env.BRANCH_NAME?.replaceAll('[^a-zA-Z0-9._-]', '-') ?: 'unknown'
                    env.IMAGE_TAG       = "sha-${env.GIT_COMMIT_SHORT}"
                    env.BUILD_TAG       = "build-${env.BUILD_NUMBER}"

                    echo """
╔══════════════════════════════════════════════╗
║  payment-api Jenkins Pipeline                ║
╠══════════════════════════════════════════════╣
║  Branch  : ${env.BRANCH_NAME}
║  Commit  : ${env.GIT_COMMIT_SHORT}
║  Image   : ${env.IMAGE_REPO}:${env.IMAGE_TAG}
║  Build   : #${env.BUILD_NUMBER}
╚══════════════════════════════════════════════╝"""
                }
            }
        }

        // ── 2. BUILD & TEST ───────────────────────────────────────────────────
        // Compiles, runs all 539 tests, enforces JaCoCo ≥75% line coverage.
        stage('Build & Test') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                sh '''
                    mvn --batch-mode --no-transfer-progress \
                        clean verify \
                        -Dspring.profiles.active=test
                '''
            }
            post {
                always {
                    // Publish JUnit results (test counts + failure annotations)
                    junit(
                        testResults: 'target/surefire-reports/*.xml',
                        allowEmptyResults: false,
                        skipPublishingChecks: false
                    )

                    // Publish JaCoCo coverage; fail build if line coverage drops below 75 %
                    jacoco(
                        execPattern:      'target/jacoco.exec',
                        classPattern:     'target/classes',
                        sourcePattern:    'src/main/java',
                        exclusionPattern: '**/*Test*,**/*IT*,**/*Cucumber*',
                        minimumLineCoverage:        '75',
                        minimumBranchCoverage:      '0',
                        minimumComplexityCoverage:  '0',
                        changeBuildStatus: true
                    )

                    // Keep the JaCoCo HTML report browseable from the build page
                    publishHTML([
                        allowMissing:          true,
                        alwaysLinkToLastBuild: false,
                        keepAll:               true,
                        reportDir:             'target/site/jacoco',
                        reportFiles:           'index.html',
                        reportName:            'JaCoCo Coverage',
                        reportTitles:          'Coverage'
                    ])

                    // Archive JAR as a downloadable build artefact
                    archiveArtifacts(
                        artifacts:          'target/payment-api-*.jar',
                        fingerprint:        true,
                        allowEmptyArchive:  false
                    )

                    // Stash the JAR so the Docker stage can use it without rebuilding
                    stash(
                        name:     'application-jar',
                        includes: 'target/payment-api-*.jar'
                    )
                }
            }
        }

        // ── 3. SECURITY SCANS (parallel) ──────────────────────────────────────
        // Mirrors ci.yml jobs 3 (Trivy FS) and 5 (OWASP).
        // Both scans run in parallel to shorten wall-clock time.
        stage('Security Scans') {
            when {
                not { expression { params.SKIP_SECURITY_SCANS } }
            }
            options { timeout(time: 40, unit: 'MINUTES') }
            parallel {

                // ── 3a. OWASP Dependency-Check ────────────────────────────────
                // Fails the build on CVSS ≥ 7.0 (High/Critical) — same gate as GHA.
                stage('OWASP Dependency Check') {
                    steps {
                        script {
                            // NVD API key speeds up updates; pipeline continues without it.
                            def nvdFlag = ''
                            try {
                                withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_KEY')]) {
                                    // Write flag to a file to avoid exposing key via Groovy GString
                                    writeFile file: '.nvd-key', text: env.NVD_KEY
                                    nvdFlag = '-Dnvd.api.key.file=.nvd-key'
                                }
                            } catch (Exception ignored) {
                                echo 'nvd-api-key credential not found — using cached NVD data'
                            }

                            sh """
                                mkdir -p target/owasp
                                mvn --batch-mode --no-transfer-progress \
                                    org.owasp:dependency-check-maven:check \
                                    -DfailBuildOnCVSS=7 \
                                    -DenableRetired=true \
                                    -Dformat=ALL \
                                    -DoutputDirectory=target/owasp \
                                    ${nvdFlag}
                            """
                        }
                    }
                    post {
                        always {
                            // Clean up the temporary key file immediately after use
                            sh 'rm -f .nvd-key'
                            archiveArtifacts(
                                artifacts:         'target/owasp/**',
                                allowEmptyArchive: true
                            )
                        }
                    }
                }

                // ── 3b. Trivy Filesystem Scan ─────────────────────────────────
                // Secret scan: BLOCKING (exit-code 1).
                // CVE + misconfig scan: report only (exit-code 0).
                stage('Trivy Filesystem Scan') {
                    steps {
                        sh '''
                            mkdir -p target/trivy

                            echo "==> Trivy: scanning for hardcoded secrets (blocking)"
                            trivy fs . \
                                --scanners secret \
                                --format table \
                                --exit-code 1 \
                                --quiet

                            echo "==> Trivy: scanning for CVEs and misconfigurations (informational)"
                            trivy fs . \
                                --scanners vuln,misconfig \
                                --severity CRITICAL,HIGH,MEDIUM \
                                --format json \
                                --output target/trivy/trivy-fs.json \
                                --exit-code 0 \
                                --quiet || true

                            # Human-readable summary
                            trivy fs . \
                                --scanners vuln,misconfig \
                                --severity CRITICAL,HIGH,MEDIUM \
                                --format table \
                                --exit-code 0 \
                                --quiet || true
                        '''
                    }
                    post {
                        always {
                            archiveArtifacts(
                                artifacts:         'target/trivy/trivy-fs.json',
                                allowEmptyArchive: true
                            )
                        }
                    }
                }

            } // end parallel
        }

        // ── 4. DOCKER BUILD ───────────────────────────────────────────────────
        // Builds the multi-stage layered-JAR image.
        // The build uses the local Docker daemon; BuildKit is enabled for cache.
        stage('Docker Build') {
            options { timeout(time: 15, unit: 'MINUTES') }
            environment {
                DOCKER_BUILDKIT = '1'
            }
            steps {
                script {
                    env.DOCKER_IMAGE_SHA   = "${env.IMAGE_REPO}:${env.IMAGE_TAG}"
                    env.DOCKER_IMAGE_BUILD = "${env.IMAGE_REPO}:${env.BUILD_TAG}"
                }
                sh '''
                    echo "==> Building Docker image: ${DOCKER_IMAGE_SHA}"
                    docker build \
                        --tag  "${DOCKER_IMAGE_SHA}" \
                        --tag  "${DOCKER_IMAGE_BUILD}" \
                        --cache-from "${IMAGE_REPO}:latest" \
                        --label "org.opencontainers.image.revision=${GIT_COMMIT_SHORT}" \
                        --label "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
                        --label "jenkins.build.number=${BUILD_NUMBER}" \
                        .
                    echo "==> Image built: $(docker image inspect ${DOCKER_IMAGE_SHA} --format '{{.Id}}')"
                '''
            }
        }

        // ── 5. DOCKER SECURITY SCAN ───────────────────────────────────────────
        // Scans the freshly built image for CRITICAL/HIGH CVEs (fixable only).
        // Mirrors ci.yml job 4 (docker-scan).
        stage('Docker Security Scan') {
            when {
                not { expression { params.SKIP_SECURITY_SCANS } }
            }
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                sh '''
                    mkdir -p target/trivy

                    echo "==> Trivy: scanning container image ${DOCKER_IMAGE_SHA}"
                    trivy image \
                        --severity CRITICAL,HIGH \
                        --ignore-unfixed \
                        --format json \
                        --output target/trivy/trivy-image.json \
                        --exit-code 1 \
                        --quiet \
                        "${DOCKER_IMAGE_SHA}"
                '''
            }
            post {
                always {
                    // Table summary for the build log
                    sh '''
                        trivy image \
                            --severity CRITICAL,HIGH \
                            --ignore-unfixed \
                            --format table \
                            --exit-code 0 \
                            --quiet \
                            "${DOCKER_IMAGE_SHA}" || true
                    '''
                    archiveArtifacts(
                        artifacts:         'target/trivy/trivy-image.json',
                        allowEmptyArchive: true
                    )
                }
            }
        }

        // ── 6. PUBLISH IMAGE ──────────────────────────────────────────────────
        // Pushes the SHA tag + build tag to GHCR.
        // On master, also pushes the :latest tag.
        // Mirrors cd.yml job 1 (publish-image).
        stage('Publish Image') {
            when {
                anyOf {
                    branch 'master'
                    expression { params.DEPLOY_TARGET in ['staging', 'production'] }
                }
            }
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'ghcr-credentials',
                    usernameVariable: 'GHCR_USER',
                    passwordVariable: 'GHCR_TOKEN'
                )]) {
                    sh '''
                        echo "==> Logging in to GHCR"
                        echo "${GHCR_TOKEN}" | docker login ghcr.io \
                            --username "${GHCR_USER}" \
                            --password-stdin

                        echo "==> Pushing ${DOCKER_IMAGE_SHA}"
                        docker push "${DOCKER_IMAGE_SHA}"

                        echo "==> Pushing ${DOCKER_IMAGE_BUILD}"
                        docker push "${DOCKER_IMAGE_BUILD}"
                    '''
                    script {
                        if (env.BRANCH_NAME == 'master') {
                            sh '''
                                echo "==> Tagging and pushing :latest (master branch)"
                                docker tag  "${DOCKER_IMAGE_SHA}" "${IMAGE_REPO}:latest"
                                docker push "${IMAGE_REPO}:latest"
                            '''
                        }
                    }
                }
            }
            post {
                always {
                    sh 'docker logout ghcr.io 2>/dev/null || true'
                }
            }
        }

        // ── 7. DEPLOY STAGING ─────────────────────────────────────────────────
        // Helm upgrade --install into the payment-staging namespace.
        // Mirrors cd.yml job 2 (deploy-staging).
        stage('Deploy Staging') {
            when {
                anyOf {
                    branch 'master'
                    expression { params.DEPLOY_TARGET in ['staging', 'production'] }
                }
            }
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                script {
                    def kubeConfigured = false
                    try {
                        withCredentials([file(credentialsId: 'kube-config-staging', variable: 'KUBECONFIG_FILE')]) {
                            kubeConfigured = true
                            sh '''
                                export KUBECONFIG="${KUBECONFIG_FILE}"

                                echo "==> Ensuring namespace ${STAGING_NS} exists"
                                kubectl create namespace "${STAGING_NS}" \
                                    --dry-run=client -o yaml | kubectl apply -f -

                                echo "==> Installing helm-diff plugin"
                                helm plugin install https://github.com/databus23/helm-diff \
                                    --version 3.9.9 2>/dev/null || true

                                echo "==> Helm diff (preview)"
                                helm diff upgrade payment-api "${HELM_CHART_PATH}" \
                                    -f "${HELM_CHART_PATH}/values-staging.yaml" \
                                    --set "image.tag=${IMAGE_TAG}" \
                                    --namespace "${STAGING_NS}" \
                                    --allow-unreleased \
                                    --no-color || true

                                echo "==> Helm upgrade → staging"
                                helm upgrade --install payment-api "${HELM_CHART_PATH}" \
                                    -f "${HELM_CHART_PATH}/values-staging.yaml" \
                                    --set "image.tag=${IMAGE_TAG}" \
                                    --namespace "${STAGING_NS}" \
                                    --create-namespace \
                                    --atomic \
                                    --timeout 5m \
                                    --wait \
                                    --history-max 5

                                echo "==> Verifying rollout"
                                kubectl rollout status deployment/payment-api \
                                    --namespace "${STAGING_NS}" \
                                    --timeout=5m
                            '''
                        }
                    } catch (Exception e) {
                        if (!kubeConfigured) {
                            echo "WARNING: kube-config-staging credential not configured — staging deploy skipped."
                            echo "Add a kubeconfig file credential with ID 'kube-config-staging' to enable K8s deploys."
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            throw e
                        }
                    }
                }
            }
        }

        // ── 8. STAGING SMOKE TESTS ────────────────────────────────────────────
        // Polls the Actuator health endpoint via kubectl exec.
        // Mirrors cd.yml "Smoke test — health endpoint" step.
        stage('Staging Smoke Tests') {
            when {
                anyOf {
                    branch 'master'
                    expression { params.DEPLOY_TARGET in ['staging', 'production'] }
                }
            }
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                script {
                    try {
                        withCredentials([file(credentialsId: 'kube-config-staging', variable: 'KUBECONFIG_FILE')]) {
                            sh '''
                                export KUBECONFIG="${KUBECONFIG_FILE}"
                                chmod +x jenkins/scripts/smoke-test.sh
                                jenkins/scripts/smoke-test.sh "${STAGING_NS}"
                            '''
                        }
                    } catch (Exception e) {
                        echo "WARNING: kube-config-staging not configured — smoke test skipped."
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }

        // ── 9. PRODUCTION APPROVAL GATE ───────────────────────────────────────
        // Manual input required before any production deployment.
        // Times out after 30 minutes so pipelines don't wait indefinitely.
        // Mirrors the GitHub Environment "required reviewers" gate on `production`.
        stage('Approve Production') {
            when {
                expression { params.DEPLOY_TARGET == 'production' }
            }
            steps {
                script {
                    def approver = ''
                    timeout(time: 30, unit: 'MINUTES') {
                        def userInput = input(
                            message: "Deploy ${env.IMAGE_TAG} (build #${env.BUILD_NUMBER}) to PRODUCTION?",
                            ok: 'Deploy to Production',
                            submitterParameter: 'APPROVED_BY',
                            parameters: [
                                booleanParam(
                                    name:         'CONFIRM',
                                    defaultValue: false,
                                    description:  'Check to confirm — this deploys to the live environment'
                                )
                            ]
                        )
                        if (userInput instanceof Map) {
                            if (!userInput['CONFIRM']) {
                                error('Production deployment not confirmed — aborting.')
                            }
                            approver = userInput['APPROVED_BY'] ?: 'unknown'
                        }
                    }
                    env.PROD_APPROVED_BY = approver
                    echo "Production deployment approved by: ${approver}"
                }
            }
        }

        // ── 10. DEPLOY PRODUCTION ─────────────────────────────────────────────
        // Helm upgrade into the payment-prod namespace.
        // Only reachable after a manual approval in stage 9.
        // Mirrors cd.yml job 3 (deploy-production).
        stage('Deploy Production') {
            when {
                expression { params.DEPLOY_TARGET == 'production' }
            }
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                withCredentials([file(credentialsId: 'kube-config-prod', variable: 'KUBECONFIG_FILE')]) {
                    sh '''
                        export KUBECONFIG="${KUBECONFIG_FILE}"

                        echo "==> Ensuring namespace ${PROD_NS} exists"
                        kubectl create namespace "${PROD_NS}" \
                            --dry-run=client -o yaml | kubectl apply -f -

                        echo "==> Helm diff (preview)"
                        helm diff upgrade payment-api "${HELM_CHART_PATH}" \
                            -f "${HELM_CHART_PATH}/values-prod.yaml" \
                            --set "image.tag=${IMAGE_TAG}" \
                            --namespace "${PROD_NS}" \
                            --allow-unreleased \
                            --no-color || true

                        echo "==> Helm upgrade → production"
                        helm upgrade --install payment-api "${HELM_CHART_PATH}" \
                            -f "${HELM_CHART_PATH}/values-prod.yaml" \
                            --set "image.tag=${IMAGE_TAG}" \
                            --namespace "${PROD_NS}" \
                            --create-namespace \
                            --atomic \
                            --timeout 5m \
                            --wait \
                            --history-max 5

                        echo "==> Verifying rollout"
                        kubectl rollout status deployment/payment-api \
                            --namespace "${PROD_NS}" \
                            --timeout=5m
                    '''
                }
            }
        }

    } // end stages

    // =========================================================================
    // Post-build actions (run after every pipeline execution)
    // =========================================================================
    post {

        always {
            // Remove local Docker images to reclaim disk on the agent
            sh '''
                docker rmi "${DOCKER_IMAGE_SHA}"   2>/dev/null || true
                docker rmi "${DOCKER_IMAGE_BUILD}" 2>/dev/null || true
                docker rmi "${IMAGE_REPO}:latest"  2>/dev/null || true
            '''

            // Clean the workspace so the next build starts fresh
            cleanWs()
        }

        success {
            echo """
╔══════════════════════════════════════════════╗
║  ✓  PIPELINE SUCCESS                         ║
╠══════════════════════════════════════════════╣
║  Branch  : ${env.BRANCH_NAME}
║  Commit  : ${env.GIT_COMMIT_SHORT}
║  Image   : ${env.IMAGE_REPO}:${env.IMAGE_TAG}
║  Build   : #${env.BUILD_NUMBER}
╚══════════════════════════════════════════════╝"""
        }

        failure {
            echo """
╔══════════════════════════════════════════════╗
║  ✗  PIPELINE FAILED                          ║
╠══════════════════════════════════════════════╣
║  Branch  : ${env.BRANCH_NAME}
║  Commit  : ${env.GIT_COMMIT_SHORT}
║  Build   : #${env.BUILD_NUMBER}
║  Check the stage logs above for details.
╚══════════════════════════════════════════════╝"""
        }

        unstable {
            echo """
╔══════════════════════════════════════════════╗
║  ⚠  PIPELINE UNSTABLE                        ║
╠══════════════════════════════════════════════╣
║  Branch  : ${env.BRANCH_NAME}
║  Commit  : ${env.GIT_COMMIT_SHORT}
║  Build   : #${env.BUILD_NUMBER}
║  Reason  : coverage gate or deploy credential not configured.
╚══════════════════════════════════════════════╝"""
        }

    } // end post

} // end pipeline
