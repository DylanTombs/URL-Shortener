# Phase 3 — CI/CD Pipeline

## Goal
A complete GitHub Actions pipeline: build → test → push image to ECR →
rolling deploy to ECS dev (automatic) → deploy to ECS prod (manual approval).
No long-lived AWS credentials stored in GitHub Secrets.

---

## 3.1 Dockerfile (Multi-Stage)

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Why multi-stage:**
- Build tools (Maven, full JDK) are not shipped in the production image
- Runtime image uses JRE21 alpine — ~180MB vs ~500MB JDK image
- Runs as non-root user (`appuser`) — required ECS security best practice

**Why layer ordering matters:**
`COPY pom.xml` + `dependency:go-offline` before `COPY src/` means Docker's layer cache
reuses the dependency download layer when only source files change — critical for fast CI builds.

**Files:** `Dockerfile` (new, at repo root)

---

## 3.2 GitHub OIDC Authentication

No long-lived access keys stored in GitHub Secrets. GitHub Actions assumes an IAM role
via OIDC federation.

### IAM OIDC Provider + Role (Terraform — new `terraform/modules/github-oidc/`)

```hcl
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

resource "aws_iam_role" "github_actions" {
  name = "github-actions-url-shortener-${var.environment}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:OWNER/url-shortener:*"
        }
      }
    }]
  })
}
```

### Required IAM Permissions on the Role

```hcl
# ECR push
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload

# ECS deploy
ecs:RegisterTaskDefinition
ecs:UpdateService
ecs:DescribeServices
ecs:DescribeTaskDefinition
ecs:ListTaskDefinitions

# Pass roles to ECS
iam:PassRole  # on ecs_execution_role and ecs_task_role ARNs only
```

### GitHub Secrets Required

| Secret | Value |
|--------|-------|
| `AWS_ROLE_ARN_DEV` | IAM role ARN for dev deployments |
| `AWS_ROLE_ARN_PROD` | IAM role ARN for prod deployments |
| `ECR_REGISTRY` | `<account-id>.dkr.ecr.<region>.amazonaws.com` |
| `ECR_REPOSITORY` | `url-shortener` |
| `ECS_CLUSTER_DEV` | ECS cluster name (dev) |
| `ECS_SERVICE_DEV` | ECS service name (dev) |
| `ECS_CLUSTER_PROD` | ECS cluster name (prod) |
| `ECS_SERVICE_PROD` | ECS service name (prod) |
| `AWS_REGION` | e.g. `us-east-1` |

---

## 3.3 `.github/workflows/ci.yml`

**Triggers:** Push to `main`, all pull requests.

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Run tests with coverage
        run: mvn verify

      - name: Upload JaCoCo report
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: target/site/jacoco/
          retention-days: 7

  docker-build-push:
    name: Build & Push Docker Image
    needs: build-and-test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    permissions:
      id-token: write
      contents: read
    outputs:
      image: ${{ steps.build.outputs.image }}

    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN_DEV }}
          aws-region: ${{ secrets.AWS_REGION }}

      - uses: aws-actions/amazon-ecr-login@v2

      - name: Build, tag, and push image
        id: build
        env:
          REGISTRY: ${{ secrets.ECR_REGISTRY }}
          REPOSITORY: ${{ secrets.ECR_REPOSITORY }}
        run: |
          IMAGE_TAG=${{ github.sha }}
          IMAGE=$REGISTRY/$REPOSITORY:$IMAGE_TAG
          docker build -t $IMAGE .
          docker tag $IMAGE $REGISTRY/$REPOSITORY:latest
          docker push $IMAGE
          docker push $REGISTRY/$REPOSITORY:latest
          echo "image=$IMAGE" >> $GITHUB_OUTPUT

      - name: Scan image for vulnerabilities
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: ${{ steps.build.outputs.image }}
          format: table
          exit-code: '1'
          severity: CRITICAL,HIGH

  deploy-dev:
    name: Deploy to Dev
    needs: docker-build-push
    runs-on: ubuntu-latest
    environment: dev
    permissions:
      id-token: write
      contents: read

    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN_DEV }}
          aws-region: ${{ secrets.AWS_REGION }}

      - name: Deploy to ECS (dev)
        run: |
          aws ecs update-service \
            --cluster ${{ secrets.ECS_CLUSTER_DEV }} \
            --service ${{ secrets.ECS_SERVICE_DEV }} \
            --force-new-deployment

          aws ecs wait services-stable \
            --cluster ${{ secrets.ECS_CLUSTER_DEV }} \
            --services ${{ secrets.ECS_SERVICE_DEV }}
```

**Notes:**
- Testcontainers starts real Postgres and Redis via the Docker socket on `ubuntu-latest`
  (Docker is pre-installed — no extra `services:` config needed)
- JaCoCo enforces 80% at `mvn verify` — build fails before docker push if coverage drops
- Trivy scan runs after push so it scans the final layered image; `exit-code: 1` blocks
  the pipeline on CRITICAL or HIGH CVEs

---

## 3.4 `.github/workflows/cd.yml` — Production Deployment

**Trigger:** `workflow_dispatch` (manual trigger from GitHub UI) or called from `ci.yml`.

```yaml
name: CD — Deploy to Production

on:
  workflow_dispatch:
    inputs:
      image_tag:
        description: 'Image tag to deploy (SHA or latest)'
        required: true
        default: latest

jobs:
  deploy-prod:
    name: Deploy to Production
    runs-on: ubuntu-latest
    environment: production   # blocks until a required reviewer approves in GitHub UI
    permissions:
      id-token: write
      contents: read

    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN_PROD }}
          aws-region: ${{ secrets.AWS_REGION }}

      - name: Deploy to ECS (prod)
        run: |
          aws ecs update-service \
            --cluster ${{ secrets.ECS_CLUSTER_PROD }} \
            --service ${{ secrets.ECS_SERVICE_PROD }} \
            --force-new-deployment

          aws ecs wait services-stable \
            --cluster ${{ secrets.ECS_CLUSTER_PROD }} \
            --services ${{ secrets.ECS_SERVICE_PROD }}

      - name: Validate health
        run: |
          curl -sf https://${{ secrets.PROD_ALB_DNS }}/actuator/health | \
            jq -e '.status == "UP"'
```

**GitHub Environment setup (one-time manual step):**
1. Repository Settings → Environments → New environment → name: `production`
2. Add required reviewers (yourself or team leads)
3. This blocks the `deploy-prod` job until a reviewer approves in the GitHub UI

---

## 3.5 Rollback Strategy

ECS stores every task definition revision. The deployment circuit breaker
(`deployment_circuit_breaker { enable = true; rollback = true }` in Terraform) automatically
rolls back if health checks fail during deployment.

**Manual rollback:**
```bash
# 1. Find the last stable revision
aws ecs describe-services \
  --cluster url-shortener-prod \
  --services url-shortener \
  --query 'services[0].deployments'

# 2. Roll back to a specific revision (N-1)
PREVIOUS_REVISION=$(aws ecs list-task-definitions \
  --family-prefix url-shortener-prod \
  --sort DESC \
  --query 'taskDefinitionArns[1]' \
  --output text)

aws ecs update-service \
  --cluster url-shortener-prod \
  --service url-shortener \
  --task-definition $PREVIOUS_REVISION

# 3. Wait for stability
aws ecs wait services-stable \
  --cluster url-shortener-prod \
  --services url-shortener
```

---

## Architectural Challenges

### 1. Testcontainers in GitHub Actions
`ubuntu-latest` runners have Docker pre-installed. Testcontainers auto-detects
`/var/run/docker.sock` — no `docker:dind` service configuration needed. Maven's
`failsafe` plugin runs `*IT.java` tests during the `verify` phase.

### 2. Image Tag Strategy
SHA-based tags (`${{ github.sha }}`) give exact traceability — every image maps 1:1 to
a commit. The `latest` tag is also pushed so ECS `--force-new-deployment` picks up the
new image without an explicit task definition update. For prod, always deploy by SHA
(pass via `workflow_dispatch` input) to avoid accidental `latest` drift.

### 3. `aws ecs wait services-stable` Timeout
Default: 40 polls × 15 seconds = 10 minutes. If ECS doesn't stabilize, the pipeline
fails — which is the correct signal. Common causes: health check path wrong, OOM kill,
failed Secrets Manager fetch. Always check CloudWatch Logs first.

### 4. ECS Task Definition Updates
`--force-new-deployment` triggers ECS to pull the `latest` image and start a rolling
replacement. ECS keeps the old tasks running until the new ones pass health checks —
zero-downtime by default. The circuit breaker reverts automatically on failure.

---

## Files to Create

| File | Purpose |
|------|---------|
| `Dockerfile` | Multi-stage build at repo root |
| `.github/workflows/ci.yml` | Build, test, push, deploy-dev |
| `.github/workflows/cd.yml` | Production deploy with approval gate |
| `terraform/modules/github-oidc/main.tf` | OIDC provider + IAM role |
| `terraform/modules/github-oidc/variables.tf` | Input variables |
| `terraform/modules/github-oidc/outputs.tf` | Role ARN output |

---

## Verification

- Push to a feature branch triggers `build-and-test` only (no deploy)
- Merge to `main` triggers all three jobs: build-test → docker-push → deploy-dev
- CloudWatch Logs for the dev ECS service shows a new container start event
- `curl https://<dev-alb>/actuator/health` returns `{"status":"UP"}`
- Trivy scan shows zero CRITICAL or HIGH CVEs
- Production deploy requires reviewer approval before the job starts
- After prod deploy, `aws ecs describe-services` shows `runningCount == desiredCount`
