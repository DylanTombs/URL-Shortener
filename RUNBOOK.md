# Runbook

Operational procedures for the URL Shortener service. All commands are copy-pasteable; replace values in `UPPER_CASE` with environment-specific values from `terraform output`.

---

## Local Development Setup

**Prerequisites:** Java 21, Maven 3.9+, Docker Desktop running

```bash
# Start local Postgres and Redis
docker run -d --name urlshortener-db \
  -p 5432:5432 \
  -e POSTGRES_DB=urlshortener \
  -e POSTGRES_USER=urlshortener \
  -e POSTGRES_PASSWORD=secret \
  postgres:16-alpine

docker run -d --name urlshortener-redis \
  -p 6379:6379 \
  redis:7-alpine

# Run the application (Flyway applies migrations on startup)
mvn spring-boot:run

# Run full test suite (Testcontainers starts its own Postgres + Redis)
mvn verify

# Stop and remove local containers
docker rm -f urlshortener-db urlshortener-redis
```

**Verify the app is running:**
```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}

curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}' | jq .
# Expected: {"code":"...","shortUrl":"http://localhost:8080/...","expiresAt":null}
```

---

## Provisioning Infrastructure (First Time)

Run once per environment. Requires AWS credentials with admin permissions.

```bash
# Bootstrap S3 + DynamoDB for Terraform remote state (one-time, manual)
aws s3api create-bucket --bucket url-shortener-terraform-state --region us-east-1
aws s3api put-bucket-versioning \
  --bucket url-shortener-terraform-state \
  --versioning-configuration Status=Enabled
aws dynamodb create-table \
  --table-name url-shortener-terraform-lock \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

# Deploy dev environment
cd terraform/environments/dev
terraform init
terraform plan -var="github_repo=YOUR_ORG/url-shortener"
terraform apply -var="github_repo=YOUR_ORG/url-shortener"

# Capture outputs needed for GitHub Secrets
terraform output ecr_repository_url
terraform output ecs_cluster_name
terraform output ecs_service_name
terraform output alb_dns_name
```

---

## Deploy to Dev (Manual)

Trigger the CD workflow from the GitHub UI:

```
GitHub → Actions → "CD — Build, Push & Deploy" → Run workflow → (leave image_tag blank for HEAD)
```

Or trigger via CLI:
```bash
gh workflow run cd.yml --ref main
```

Monitor progress:
```bash
gh run list --workflow=cd.yml --limit 5
gh run watch
```

---

## Deploy to Production

Production deploys require a manual approval in the `production` GitHub Environment.

```bash
# Trigger CD workflow with a specific SHA
gh workflow run cd.yml \
  --ref main \
  --field image_tag=SHA_TO_DEPLOY
```

1. Approve the deployment in GitHub → Actions → (running workflow) → Review deployments
2. Monitor ECS:
```bash
aws ecs describe-services \
  --cluster url-shortener-prod \
  --services url-shortener \
  --query 'services[0].{running:runningCount,desired:desiredCount,status:status}'
```
3. Validate health:
```bash
curl -sf https://sho.rt/actuator/health | jq .
# Expected: {"status":"UP"}
```

---

## Rollback Procedure

```bash
# List the 5 most recent task definition revisions
aws ecs list-task-definitions \
  --family-prefix url-shortener-prod \
  --sort DESC \
  --query 'taskDefinitionArns[:5]'

# Roll back to a previous revision (replace N with the revision number)
aws ecs update-service \
  --cluster url-shortener-prod \
  --service url-shortener \
  --task-definition url-shortener-prod:N

# Wait for rollback to complete (polls every 15s, up to 10 min)
aws ecs wait services-stable \
  --cluster url-shortener-prod \
  --services url-shortener

# Confirm
curl -sf https://sho.rt/actuator/health | jq .
```

---

## Investigating a Latency Spike

1. **Open the CloudWatch dashboard** — `url-shortener-prod-overview` — identify which widget spiked.

2. **Check cache hit rate widget.** A drop in cache hit rate means requests are hitting RDS instead of Redis. This is the most common cause of p99 spikes.

3. **If cache hit rate dropped:**
   - Check ElastiCache in the AWS console — look for `EngineCPUUtilization` or `CurrConnections` anomalies
   - ECS Exec into a task to probe Redis directly:
     ```bash
     aws ecs execute-command \
       --cluster url-shortener-prod \
       --task TASK_ID \
       --container url-shortener \
       --interactive \
       --command "redis-cli -h ELASTICACHE_ENDPOINT ping"
     ```

4. **If cache hit rate is normal but latency is high:**
   - Check RDS Performance Insights → Top SQL → sort by average latency
   - Look for lock wait events on the primary (click count writes)

5. **Check for ECS scale-out event.** New tasks start cold — no warm connections, no JVM JIT compilation. A scale-out event coinciding with a traffic spike causes a temporary p99 spike. Confirm in ECS Events tab.

6. **CloudWatch Logs Insights** — find slow individual requests:
   ```
   fields @timestamp, traceId, latencyMs, cacheHit, @message
   | filter latencyMs > 200
   | sort @timestamp desc
   | limit 50
   ```

---

## Investigating High Error Rate

1. **Check the ALB 5xx alarm** in the CloudWatch dashboard. Identify the time window.

2. **ECS task logs:**
   ```
   fields @timestamp, traceId, level, @message
   | filter level = "ERROR"
   | sort @timestamp desc
   | limit 50
   ```

3. **Check Redis connectivity:**
   ```bash
   aws ecs execute-command \
     --cluster url-shortener-prod \
     --task TASK_ID \
     --container url-shortener \
     --interactive \
     --command "redis-cli -h ELASTICACHE_ENDPOINT ping"
   ```

4. **Check RDS connectivity:**
   ```bash
   aws ecs execute-command \
     --cluster url-shortener-prod \
     --task TASK_ID \
     --container url-shortener \
     --interactive \
     --command "psql $SPRING_DATASOURCE_URL -c 'SELECT 1'"
   ```

5. **Check ECS task health.** If tasks are in `STOPPED` state with `OutOfMemoryError` in exit reason, the task memory limit needs to be raised in Terraform (`task_memory`).

---

## Scaling the Service

**ECS (horizontal):** Target tracking auto-scales at 60% CPU. To force a scale-out:
```bash
aws ecs update-service \
  --cluster url-shortener-prod \
  --service url-shortener \
  --desired-count 5
```

**Read replica:** If read replica CPU exceeds 60% sustained, add a second read replica in `terraform/environments/prod/main.tf`:
```hcl
# In module "rds": increase replica_instance_class or add a second replica block
```
Then: `terraform apply`

**Redis:** ElastiCache prod is a 2-node replication group. If `CurrConnections` alarms fire, add read replicas:
```bash
aws elasticache increase-replica-count \
  --replication-group-id url-shortener-prod \
  --new-replica-count 3 \
  --apply-immediately
```

---

## Applying Database Migrations

Flyway runs automatically at application startup (`spring.flyway.enabled=true`). Steps for a new migration:

1. Add `db/migration/V{N}__description.sql` (e.g. `V2__add_title_column.sql`)
2. Test locally: `mvn spring-boot:run` — Flyway applies the migration on startup
3. Merge to `main` and trigger a deploy via CD workflow
4. Flyway applies the migration before the new application version starts serving traffic

**For destructive migrations** (column drops, constraint changes): deploy in two steps — first deploy with backward-compatible migration (e.g. nullable column), then deploy with application change, then deploy cleanup migration.

---

## Redis Cache Flush

Only perform this if stale data is causing incorrect redirects. Expect a brief spike in DB reads and p99 latency as the cache warms up.

```bash
# Identify a running task
TASK_ID=$(aws ecs list-tasks \
  --cluster url-shortener-prod \
  --service-name url-shortener \
  --query 'taskArns[0]' \
  --output text)

# Flush the cache namespace (flushes only the application DB, not all Redis data)
aws ecs execute-command \
  --cluster url-shortener-prod \
  --task $TASK_ID \
  --container url-shortener \
  --interactive \
  --command "redis-cli -h ELASTICACHE_ENDPOINT FLUSHDB"
```
