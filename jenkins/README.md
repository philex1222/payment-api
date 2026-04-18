# Jenkins CI/CD — payment-api

This directory contains everything needed to run the payment-api pipeline on Jenkins
alongside the existing GitHub Actions workflows. Jenkins mirrors (and extends) the
GitHub Actions CI/CD without duplicating it — both pipelines can run independently.

---

## Pipeline overview

```
All branches:
  Checkout → Build & Test → Security Scans (parallel) → Docker Build → Docker Scan

master / manual staging:
  └─ Publish Image → Deploy Staging → Staging Smoke Tests

manual production (DEPLOY_TARGET=production):
  └─ Approve Production (30-min timeout) → Deploy Production
```

The `DEPLOY_TARGET` build parameter controls deployment:

| Value        | What runs |
|---|---|
| `none`       | CI only — build, test, security, Docker build/scan |
| `staging`    | CI + image push + staging deploy + smoke tests |
| `production` | Everything above + manual approval gate + production deploy |

---

## Prerequisites

### Jenkins plugins

Install via **Manage Jenkins → Plugins → Available**:

| Plugin | Purpose |
|---|---|
| Pipeline | Declarative pipeline DSL |
| Docker Pipeline | `docker.build`, `docker.withRegistry` steps |
| Credentials Binding | `withCredentials` in pipeline |
| JUnit | Test result publishing |
| JaCoCo | Coverage enforcement and reporting |
| HTML Publisher | JaCoCo HTML report on build page |
| Timestamper | Timestamps in build logs |
| AnsiColor | Colour in build logs |
| Workspace Cleanup | `cleanWs()` step |
| Configuration as Code | JCasC support (optional) |

### Tools — Global Tool Configuration

Go to **Manage Jenkins → Global Tool Configuration** and add:

| Type  | Name          | Installer |
|---|---|---|
| JDK   | `temurin-21`  | Eclipse Temurin — JDK 21 (matches the agent image) |
| Maven | `maven-3.9`   | Maven 3.9.9 |

Alternatively, apply `jenkins/casc/jenkins.yaml` via the Configuration as Code plugin to
configure tools automatically.

---

## Build agent

The pipeline targets nodes labelled **`payment-api-agent`**. The agent needs:

- Java 21 (Eclipse Temurin — required so the agent can load controller class
  files compiled at Java 21; the Spring Boot app is still compiled with
  `--release 17` per `pom.xml`)
- Maven 3.9
- Docker CLI (with access to the host Docker socket)
- kubectl
- Helm 3
- Trivy

### Option A — pre-built Docker agent (recommended)

Build the image from `jenkins/agent/Dockerfile` and run it as a Jenkins inbound agent:

```bash
# 1. Build the agent image
docker build -t payment-api-jenkins-agent:latest jenkins/agent/

# 2. Add an inbound agent in Jenkins:
#    Manage Jenkins → Nodes → New Node
#    Type: Permanent Agent, Launch method: Launch agent by connecting it to the controller
#    Copy the secret token shown on the node's status page.

# 3. Run the agent container
docker run -d \
  --name payment-api-agent \
  --restart unless-stopped \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e JENKINS_URL=http://<JENKINS_HOST>:8080 \
  -e JENKINS_AGENT_NAME=payment-api-agent \
  -e JENKINS_SECRET=<TOKEN_FROM_STEP_2> \
  -v maven-cache:/home/jenkins/.m2 \
  payment-api-jenkins-agent:latest
```

Set the **Node labels** field to `payment-api-agent` on the new node.

### Option B — static Jenkins agent

Install the same tools on any existing agent and add the label `payment-api-agent`.

---

## Credentials

Go to **Manage Jenkins → Credentials → System → Global credentials** and add:

| ID | Type | Contents | Used for |
|---|---|---|---|
| `ghcr-credentials` | Username/Password | GitHub username + PAT (scopes: `write:packages`, `read:packages`) | Push Docker image to GHCR |
| `kube-config-staging` | Secret file | kubeconfig file for the staging cluster | Helm + kubectl against staging |
| `kube-config-prod` | Secret file | kubeconfig file for the production cluster | Helm + kubectl against production |
| `nvd-api-key` | Secret text | NVD API key (get one free at https://nvd.nist.gov/developers/request-an-api-key) | Speeds up OWASP NVD data updates (optional — pipeline continues without it) |

> Staging and production deploys are **skipped gracefully** if `kube-config-staging`
> or `kube-config-prod` credentials are not configured. The build is marked `UNSTABLE`
> rather than `FAILED` so that CI (tests, scans) still succeeds without K8s access.

---

## Creating the pipeline job

### Via UI

1. **New Item → Multibranch Pipeline** — name it `payment-api`
2. Branch Sources → **GitHub** — enter owner `philex1222`, repo `payment-api`, credentials `ghcr-credentials`
3. Build Configuration → Script Path: `Jenkinsfile`
4. Scan Multibranch Pipeline Triggers → Periodically if not otherwise run: `1 hour`
5. Save → **Scan Multibranch Pipeline Now**

### Via GitHub webhook

In the GitHub repository settings:

- **Settings → Webhooks → Add webhook**
- Payload URL: `http://<JENKINS_HOST>:8080/github-webhook/`
- Content type: `application/json`
- Events: `push`, `pull_request`

### Via JCasC

Apply `jenkins/casc/jenkins.yaml` to a JCasC-enabled Jenkins instance to create the
multibranch pipeline automatically.

---

## Running the pipeline manually

To trigger a deployment from Jenkins without a git push:

1. Open the `payment-api` multibranch job
2. Select the `master` branch build
3. Click **Build with Parameters**
4. Set `DEPLOY_TARGET` to `staging` or `production`
5. Click **Build**

For production, the pipeline will pause at the **Approve Production** stage and wait
up to 30 minutes for a human to click **Deploy to Production** and check the
confirmation box.

---

## Comparison with GitHub Actions

| Capability | GitHub Actions | Jenkins |
|---|---|---|
| Build & test (JUnit + Cucumber, full suite) | `ci.yml` job 1 | Stage 2 |
| OWASP Dependency-Check | `ci.yml` job 5 | Stage 3a (parallel) |
| Trivy filesystem + secrets | `ci.yml` job 3 | Stage 3b (parallel) |
| Docker build | `ci.yml` job 4 | Stage 4 |
| Trivy container scan | `ci.yml` job 4 | Stage 5 |
| Push to GHCR | `cd.yml` job 1 | Stage 6 |
| Deploy staging (Helm) | `cd.yml` job 2 | Stage 7 |
| Staging smoke test | `cd.yml` job 2 | Stage 8 |
| Production approval gate | GitHub Environments (required reviewers) | Stage 9 — `input` step |
| Deploy production (Helm) | `cd.yml` job 3 | Stage 10 |
| Scheduled weekly scan | `security.yml` | Add a `cron` trigger to the job (optional) |
| CodeQL SAST | `ci.yml` job 2 | Not included — CodeQL requires GitHub; use SonarQube instead |
| JaCoCo coverage gate (≥95%) | `ci.yml` job 1 (enforced in `pom.xml`) | Stage 2 (`mvn verify` + jacoco plugin) |

---

## Adding a weekly security scan

To replicate `security.yml`, add a `cron` trigger to the Jenkinsfile or configure it
via the UI:

```groovy
// In Jenkinsfile, inside options {}
triggers {
    cron('H 3 * * 1')   // Every Monday at ~03:00 (offset by Jenkins hash)
}
```

Then add a conditional stage that runs only on scheduled triggers using
`currentBuild.getBuildCauses()`.
