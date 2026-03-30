# Phase 5 — Polish and Documentation

## Goal
Load tests confirm p99 < 500ms at steady state. All required documentation
is complete. README enables a new engineer to run the service locally in under 10 minutes.

---

## 5.1 k6 Load Tests

**Directory:** `k6/`
**Prerequisites:** `brew install k6` or `docker run grafana/k6`

### `k6/smoke.js` — Sanity Check (5 VUs, 1 min)

Verifies the happy path works before running heavier tests.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const KNOWN_CODE = __ENV.KNOWN_CODE || 'aB3xK9mQ';

export const options = {
  vus:      5,
  duration: '1m',
  thresholds: {
    http_req_failed:   ['rate<0.01'],   // < 1% errors
    http_req_duration: ['p(99)<500'],   // p99 < 500ms
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${KNOWN_CODE}`, { redirects: 0 });
  check(res, {
    'status is 301':          (r) => r.status === 301,
    'Location header present': (r) => r.headers['Location'] !== undefined,
  });
  sleep(0.5);
}
```

### `k6/load.js` — Steady State (50 VUs, 10 min)

The primary pass/fail test. Must hold p99 < 500ms for 8 continuous minutes.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const KNOWN_CODE = __ENV.KNOWN_CODE || 'aB3xK9mQ';

export const options = {
  stages: [
    { duration: '2m', target: 50 },   // ramp up to 50 VUs
    { duration: '8m', target: 50 },   // hold steady state
    { duration: '1m', target: 0  },   // ramp down
  ],
  thresholds: {
    http_req_failed:   ['rate<0.01'],          // < 1% errors
    http_req_duration: ['p(95)<300', 'p(99)<500'], // p95 < 300ms, p99 < 500ms
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${KNOWN_CODE}`, { redirects: 0 });
  check(res, { 'status is 301': (r) => r.status === 301 });
  sleep(0.2);
}
```

### `k6/spike.js` — Traffic Spike (500 VUs for 1 min)

Validates behaviour under a sudden 10x traffic burst and confirms recovery.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const KNOWN_CODE = __ENV.KNOWN_CODE || 'aB3xK9mQ';

export const options = {
  stages: [
    { duration: '30s', target: 10  },  // baseline
    { duration: '30s', target: 500 },  // spike
    { duration: '60s', target: 500 },  // hold spike
    { duration: '30s', target: 10  },  // recover
    { duration: '30s', target: 0   },  // ramp down
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],    // allow up to 5% errors during spike
    http_req_duration: ['p(99)<2000'],   // p99 < 2s during spike
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${KNOWN_CODE}`, { redirects: 0 });
  check(res, { 'not 5xx': (r) => r.status < 500 });
  sleep(0.1);
}
```

### Run Commands

```bash
# Smoke test (local)
k6 run k6/smoke.js

# Load test against deployed dev
k6 run \
  -e BASE_URL=https://your-dev-alb-dns \
  -e KNOWN_CODE=<a valid code> \
  k6/load.js

# Spike test against prod (coordinate with team)
k6 run \
  -e BASE_URL=https://sho.rt \
  -e KNOWN_CODE=<a valid code> \
  k6/spike.js
```

---

## 5.2 `ARCHITECTURE.md` Outline

Write at repo root. Sections required (no placeholders):

### 1. System Overview
One paragraph. What the service does, its scale targets (handle millions of redirects/day
with p99 < 500ms), and that every component is defined as infrastructure-as-code.

### 2. Component Diagram (ASCII)
```
                        ┌──────────────────────────────────┐
                        │           AWS Account             │
                        │                                   │
Internet → [WAF] → [ALB] → [ECS Fargate tasks (2+)]       │
                              │         │                   │
                        [Redis]    [RDS Primary]            │
                                   [RDS Replica]            │
                              │                             │
                        [CloudWatch Logs + Metrics]         │
                        [Secrets Manager + SSM]             │
                        └──────────────────────────────────┘
```

### 3. Request Flow: Redirect (`GET /{code}`)
Step-by-step:
1. Client sends `GET /aB3xK9mQ`
2. WAF checks rate limit (100 req/5min per IP) — blocks if exceeded
3. ALB routes to one of the ECS tasks
4. `RateLimitInterceptor` checks Bucket4j bucket in Redis (60 req/min per IP)
5. `UrlService.resolveUrl()` checks Redis cache — **cache hit**: return immediately → 301
6. Cache miss: query RDS read replica for `code`
7. Not found → 404; expired → 410
8. Increment `click_count` on RDS primary (async-safe via `@Modifying`)
9. Write to Redis with TTL = `min(24h, time-to-expiry)`
10. Return 301 with `Location` header

### 4. Request Flow: Shorten (`POST /api/v1/urls`)
1. Validate URL format (scheme must be http/https, host must be present)
2. Generate 8-char Base62 code via `SecureRandom`
3. Check `existsByCode()` on primary — retry up to 5 times on collision
4. Persist to primary RDS
5. Return 201 with `{ code, shortUrl, expiresAt }`

### 5. Caching Architecture
- **What is cached:** Only `GET /{code}` → `longUrl` mapping
- **What is not cached:** Write path (pointless — writes are rare and the cache would be
  immediately invalidated anyway)
- **TTL formula:** `min(24 hours, remaining time until link expiry)`
  - Ensures a link expiring in 2 hours is evicted from Redis in 2 hours, not 24
  - Prevents serving a redirect to an expired URL from cache

### 6. Database Architecture
- **Primary:** All writes (shorten, click_count increment, collision check)
- **Read Replica:** All reads (redirect resolve, stats lookup)
- **Why:** URL shorteners have a read:write ratio of ~1000:1 in production. Routing reads
  to a replica keeps the primary free for writes and allows the replica to scale
  independently.
- **At 10x scale:** Add PgBouncer in front of RDS for connection pooling; consider
  promoting to a multi-reader Aurora cluster.

### 7. Network Topology
- Public subnets: ALB only
- Private subnets: ECS tasks, RDS primary + replica, ElastiCache
- NAT Gateway: outbound for ECR image pulls and CloudWatch metric publishing
- No direct internet access to any compute or data layer

### 8. Security Layers (defense in depth)
1. **WAF** — rate limit + AWS Managed Rules (SQLi, XSS, known bad inputs)
2. **Bucket4j** — endpoint-specific rate limits per IP in application layer
3. **Input Validation** — `@Valid` on all request DTOs; URL scheme + host check in service
4. **Parameterized Queries** — JPA/Hibernate; no string interpolation in SQL
5. **Secrets Management** — credentials injected from AWS Secrets Manager at container start
6. **Non-root container** — ECS tasks run as `appuser`, not root

### 9. Operational Considerations
- **Auto-scaling:** ECS target tracking on CPU 60% — scales out in 60s, scales in after
  5-minute cooldown to avoid thrashing
- **Deployment:** Rolling update, ECS circuit breaker reverts automatically on health check failure
- **Health check:** `/actuator/health` — checks both Redis and RDS connections

---

## 5.3 `DECISIONS.md` Outline

Write at repo root. Format for each entry:

```markdown
## Decision: <Title>

**Date:** 2026-03-30
**Status:** Accepted

### Context
<Why this decision was needed.>

### Options Considered
1. **Option A** — ...pros/cons...
2. **Option B** — ...pros/cons...
3. **Option C (chosen)** — ...pros/cons...

### Decision
<What was decided and why.>

### Consequences
- ✅ Benefit
- ⚠️ Tradeoff

### At 10x Scale
<What would change or break.>
```

### Decisions to Document (7 required)

**1. Base62 vs UUID vs Sequential IDs for short codes**
- UUID: globally unique but 36 chars — unusable as a short URL
- Sequential integers: short but enumerable (attacker can crawl all URLs)
- Base62 random: 8 chars, unpredictable, 62^8 = 218T combinations — correct choice

**2. Cache only the redirect path, not the write path**
- Write path cache: stale immediately; would need cache invalidation on every write — pointless complexity
- Read path cache: valid until TTL or expiry; high hit rate because the same code is clicked many times
- Decision: cache `GET /{code}` only, never `POST`

**3. Read replica for all GET reads**
- URL shorteners: reads vastly outnumber writes (people click links far more than they create them)
- Replica offloads read traffic; primary stays available for writes even under read load spikes
- At current scale: single replica. At 10x: Aurora read cluster or global tables

**4. WAF as primary rate limit + Bucket4j as secondary**
- WAF operates at the network edge — stops volumetric attacks before they consume ECS resources
- Bucket4j operates in-app — can distinguish endpoints, return structured JSON 429, and use
  Redis for distributed state across multiple ECS tasks
- Two layers are complementary, not redundant

**5. Testcontainers vs H2 for integration tests**
- H2 in Postgres compatibility mode still diverges on constraints, index types, and some SQL syntax
- In a previous project, H2 tests passed but `CREATE INDEX CONCURRENTLY` failed in prod
- Real Postgres via Testcontainers eliminates the dialect gap entirely

**6. ECS Fargate vs EC2 vs Lambda**
- Lambda: cold start adds 200–500ms latency — unacceptable for a redirect (user is waiting)
- EC2: requires capacity planning, patching, and AMI management — operational burden without benefit
- Fargate: no server management, scales per-task, pay per use, no idle capacity

**7. Synchronous vs async click count increment**
- Async (SQS + batch): decouples redirect latency from write, more resilient at extreme scale
- Synchronous: simpler, no message queue infra, at-least-once semantics free
- Decision: synchronous at current scale. Revisit when click_count writes become a measurable
  fraction of redirect latency (expected: > 100k req/s)

---

## 5.4 `RUNBOOK.md` Outline

Write at repo root. All commands must be copy-pasteable (no `<placeholder>` in commands).

### Local Development Setup
- Prerequisites: Java 21, Maven 3.9+, Docker Desktop
- Start local dependencies:
  ```bash
  docker run -d -p 5432:5432 -e POSTGRES_DB=urlshortener -e POSTGRES_PASSWORD=postgres postgres:16-alpine
  docker run -d -p 6379:6379 redis:7-alpine
  ```
- Run app: `mvn spring-boot:run`
- Run tests: `mvn verify` (starts Testcontainers automatically)

### Deploy to Dev
- Merge PR to `main` — `ci.yml` automatically builds, tests, pushes image, and deploys
- Monitor: GitHub Actions → CI workflow → `deploy-dev` job
- Validate: `curl https://<dev-alb-dns>/actuator/health`

### Deploy to Prod
- Go to GitHub Actions → CD workflow → Run workflow → enter image tag (SHA)
- Approve the deployment in the `production` environment gate
- Monitor: ECS console → `url-shortener-prod` service → Events tab
- Validate: `curl https://sho.rt/actuator/health`; check CloudWatch dashboard

### Rollback Procedure
```bash
# List recent task definition revisions
aws ecs list-task-definitions --family-prefix url-shortener-prod --sort DESC --query 'taskDefinitionArns[:5]'

# Update service to previous revision
aws ecs update-service \
  --cluster url-shortener-prod \
  --service url-shortener \
  --task-definition url-shortener-prod:<REVISION_NUMBER>

aws ecs wait services-stable --cluster url-shortener-prod --services url-shortener
```

### Investigating a Latency Spike
1. Open CloudWatch dashboard — identify which widget spiked
2. Check `url.redirect` by `cache_hit` tag — cache misses cause DB reads and inflate p99
3. If cache misses: check Redis health in ElastiCache console; `redis-cli PING` via ECS Exec
4. If cache hits are normal: check RDS Performance Insights → Top SQL → sort by wait time
5. Check ECS service scaling: did a scale-out event coincide with the spike (task startup lag)?
6. CloudWatch Logs Insights query:
   ```
   filter latencyMs > 200
   | sort @timestamp desc
   | limit 50
   ```

### Investigating High Error Rate
1. ALB access logs (S3): `grep " 5[0-9][0-9] " access_log_*`
2. ECS task logs in CloudWatch Logs Insights:
   ```
   filter log.level = "ERROR"
   | sort @timestamp desc
   | limit 50
   ```
3. Check Redis connectivity: ECS Exec into a task → `redis-cli -h <elasticache-host> PING`
4. Check RDS connectivity: ECS Exec → `psql $SPRING_DATASOURCE_URL -c "SELECT 1"`

### Scaling the Service
- **Horizontal (ECS):** Handled automatically by CPU target tracking. To force: ECS console → Update service → Desired tasks
- **Database reads:** If read replica CPU > 60% sustained, add a second read replica in Terraform
- **Database writes:** Add PgBouncer as a sidecar container in the ECS task definition
- **Redis:** ElastiCache prod is already a 2-node replication group. Add read replicas via Terraform if `CurrConnections` alarm fires

### Applying Database Migrations
Flyway runs automatically at application startup (`spring.flyway.enabled=true`).
Add a new SQL file `V2__description.sql` under `db/migration/`, merge to `main`, and the
next deploy applies the migration before the new application version starts handling traffic.

**For destructive migrations:** Set `spring.flyway.out-of-order=false` (default) and
deploy migration first to a non-production environment.

### Redis Cache Flush
Only do this if stale data is causing incorrect redirects:
```bash
# Connect via ECS Exec (requires ECS Exec to be enabled in task definition)
aws ecs execute-command \
  --cluster url-shortener-prod \
  --task <task-id> \
  --container url-shortener \
  --command "redis-cli -h <elasticache-endpoint> FLUSHDB"
```
Expect a brief spike in DB reads and latency as the cache warms up.

---

## 5.5 Verification Checklist

### k6 Pass Criteria
- [ ] `smoke.js` — all checks pass, 0 failed requests, p99 < 500ms
- [ ] `load.js` — p95 < 300ms and p99 < 500ms sustained for 8 minutes at 50 VUs
- [ ] `spike.js` — error rate < 5% during spike peak; p99 < 2000ms; latency recovers within 30s of ramp-down

### Documentation Completeness
- [ ] `ARCHITECTURE.md` — all 9 sections present, no placeholder text, diagram renders
- [ ] `DECISIONS.md` — all 7 decisions with context / options / decision / consequences / at-10x sections
- [ ] `RUNBOOK.md` — all 8 operational scenarios, all commands are runnable as-is
- [ ] `README.md` — local setup works end-to-end from a fresh clone in under 10 minutes

### Final Production Readiness Gates
- [ ] `terraform plan` on prod environment shows no unexpected resource changes
- [ ] All 4 CloudWatch alarms in `OK` state under normal load
- [ ] CloudWatch dashboard shows all 6 widgets with data (no `INSUFFICIENT_DATA`)
- [ ] p99 latency < 100ms in CloudWatch (warmed Redis cache)
- [ ] Trivy image scan shows 0 CRITICAL and 0 HIGH CVEs
- [ ] `mvn verify` reports JaCoCo line coverage ≥ 80%
- [ ] `GET /actuator/health` returns `{"status":"UP","redis":"UP","db":"UP"}`
