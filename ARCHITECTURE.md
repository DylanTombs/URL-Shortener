# Architecture

## 1. System Overview

This service shortens arbitrary URLs to 8-character Base62 codes and redirects visitors with a 302 Found. It is designed to handle millions of redirects per day at p99 < 500ms, with a read:write ratio of approximately 1000:1 typical of production URL shorteners. Every component — network, compute, database, cache, monitoring, CI/CD — is defined as Terraform infrastructure-as-code and deployed to AWS. Nothing is created manually in the console.

---

## 2. Component Diagram

```
                           ┌─────────────────────────────────────────────────┐
                           │                  AWS Account                     │
                           │                                                  │
 Internet ──► [WAF] ──► [ALB]          (public subnets)                      │
                           │                                                  │
                    ┌──────┴──────┐                                           │
                    │  ECS Task   │  (private subnets, 2+ replicas)           │
                    │             │                                           │
                    │ Spring Boot │──► [ElastiCache Redis]   (cache + rate)   │
                    │    :8080    │                                           │
                    │             │──► [RDS Replica]         (reads)          │
                    └─────────────┘                                           │
                           │          ──► [RDS Primary]      (writes)         │
                           │                                                  │
                    [CloudWatch Logs + Metrics + Alarms + Dashboard]          │
                    [Secrets Manager]  [SSM Parameter Store]                  │
                    [ECR]              [GitHub Actions OIDC]                  │
                           │                                                  │
                           └─────────────────────────────────────────────────┘
```

---

## 3. Request Flow: Redirect (`GET /{code}`)

1. Client sends `GET /aB3xK9mQ`
2. **WAF** checks rate limit (100 req / 5 min per IP) and AWS Managed Rules (SQLi, XSS) — drops request if exceeded
3. **ALB** routes to one of the ECS tasks based on least-connections
4. **`MdcRequestIdFilter`** injects a `traceId` (from `X-Request-ID` header or generated UUID) into MDC — all log lines for this request carry the same ID
5. **`RateLimitInterceptor`** resolves the client IP (via `X-Forwarded-For`) and checks the redirect bucket in Redis: 60 req/min per IP — returns 429 + `Retry-After` if exceeded
6. **`UrlService.resolveUrl()`** checks the Redis cache
   - **Cache hit** → return the `longUrl` immediately; skip to step 10
7. **Cache miss** → query **RDS read replica** for the code
8. Not found → 404; expired (current time > `expires_at`) → 410; both increment corresponding Micrometer counters
9. Write `code → longUrl` to Redis with TTL = `min(24h, remaining time to expiry)` — ensures the entry is never served after the link expires
10. **Controller** calls `urlService.incrementClickCount(code)` — `@Modifying` query on the **RDS primary**
11. Return 302 Found with `Location` header — not 301, which browsers cache permanently, breaking click counting and expiry enforcement (see DECISIONS.md Decision 8)
12. Micrometer `url.redirect` timer stops; tagged `cache_hit=true|false` — published to CloudWatch

---

## 4. Request Flow: Shorten (`POST /api/v1/urls`)

1. `@Valid` on `ShortenRequest` rejects blank URLs before reaching the service
2. **`UrlService.shortenUrl()`** calls `validateUrl()` — rejects non-http/https schemes and missing hosts (returns 422)
3. **`Base62CodeGenerator`** generates a random 62-bit long via `SecureRandom`, encodes to 8 Base62 characters
4. `urlRepository.existsByCode()` on the **RDS primary** — retries up to 5 times on collision (expected: never in practice)
5. Persist new `ShortenedUrl` row to primary
6. Increment `url.created` Micrometer counter
7. Return 201 with `{ code, shortUrl, expiresAt }`

---

## 5. Caching Architecture

**What is cached:** Only the `GET /{code}` → `longUrl` mapping. The cache is populated on the first cache miss and served on all subsequent hits.

**What is not cached:** The write path (`POST /api/v1/urls`) and stats (`GET /api/v1/urls/{code}/stats`). The write path sees each URL created once; there is no benefit to caching it.

**TTL formula:** `min(24 hours, time remaining until link expiry)`

This is the critical invariant. A link expiring in 2 hours gets a 2-hour Redis TTL, not 24 hours. If the TTL were fixed at 24 hours, a redirect could be served from Redis for up to 24 hours after the link expired — returning a 302 to the original URL when a 410 should be returned. The `min()` formula prevents this entirely.

**Why explicit cache-aside instead of `@Cacheable`:** `@Cacheable` hides the hit/miss result from the application. The Micrometer `url.redirect` timer needs a `cache_hit` tag to distinguish hit latency from miss latency in CloudWatch. Explicit cache-aside makes the hit/miss observable.

---

## 6. Database Architecture

**Primary:** All writes — URL creation, click count increment, collision check.

**Read replica:** All reads — redirect resolve, stats lookup.

**Routing mechanism:** `AbstractRoutingDataSource` determines the target based on the current `@Transactional` state. `LazyConnectionDataSourceProxy` wraps it. Without the lazy proxy, Hibernate acquires a physical connection at transaction-open time — before Spring's AOP has set the `readOnly` flag — and every request routes to the primary. The lazy proxy defers physical connection acquisition to the first SQL statement, by which point `isCurrentTransactionReadOnly()` is correct.

**Why a read replica at this scale:** URL shorteners have a read:write ratio of ~1000:1 in production. Routing reads to a replica keeps the primary free for writes even under read load spikes, and allows the replica to be scaled or replaced independently.

**At 10x scale:** Replace the single read replica with an Aurora PostgreSQL cluster (1 writer, 2+ readers). Add PgBouncer as a sidecar in the ECS task definition to pool connections — at high VU counts, every ECS task opening its own HikariCP pool creates hundreds of Postgres connections.

---

## 7. Network Topology

| Subnet type | Resources |
|---|---|
| Public | ALB only |
| Private | ECS tasks, RDS primary, RDS replica, ElastiCache |

All compute and data resources have no inbound path from the internet. The only entry point is the ALB.

**NAT Gateway** provides outbound internet access for ECS tasks: ECR image pulls on task startup, CloudWatch metric publishing, and Secrets Manager API calls. Dev uses one NAT gateway (single AZ — failure acceptable). Prod uses two (one per AZ — NAT failure must not cause a service outage).

---

## 8. Security Layers (defense in depth)

1. **WAF** — AWS-managed rate limit (100 req / 5 min per IP) + AWS Managed Rules for SQLi, XSS, and known bad inputs. First line of defence — stops volumetric attacks before they consume ECS CPU.
2. **Application rate limiting** — Bucket4j token-bucket per endpoint, state stored in Redis. Redirect: 60 req/min per IP. Create: 10 req/min per IP. Distributed across all ECS task replicas because state is in Redis, not in-process.
3. **Input validation** — `@Valid` on all request DTOs. URL scheme and host validated in `UrlService.validateUrl()` before any database interaction.
4. **Parameterized queries** — JPA/Hibernate with named parameters. No string interpolation in SQL anywhere in the codebase.
5. **Secrets management** — DB credentials stored in AWS Secrets Manager. Non-secret config (Redis host, base URL) in SSM Parameter Store. Injected into the ECS task as environment variables at container start. No plaintext secrets in the task definition or in Git.
6. **Non-root container** — The Docker image creates a dedicated `appuser` (UID 1001) and runs the JVM as that user. An attacker who achieves RCE cannot write to `/` or escalate to root.
7. **OIDC for CI/CD** — GitHub Actions assumes an IAM role via OIDC. No long-lived AWS access keys are stored in GitHub Secrets. The IAM role is scoped to the specific repository and limited to ECR push and ECS deploy actions.

---

## 9. Operational Considerations

**Auto-scaling:** ECS target tracking policy on CPU utilisation at 60%. Scales out in approximately 60 seconds; scales in after a 5-minute cooldown to avoid thrashing during traffic oscillation.

**Deployments:** Rolling update strategy. ECS replaces one task at a time, routing traffic only to tasks that pass the ALB health check (`/actuator/health`). ECS circuit breaker automatically reverts the deployment if the new tasks fail health checks — no manual intervention required for a bad deploy.

**Health check:** `/actuator/health` returns `{"status":"UP"}` to unauthenticated callers (including the ALB health check). Component-level detail (Redis and RDS connection state) is available only to authenticated callers (`show-details: when_authorized`). A task reporting anything other than `UP` is removed from the ALB target group within 30 seconds (three consecutive health check failures at 10-second intervals).

**Observability:** Every request carries a `traceId` (set by `MdcRequestIdFilter`) that appears as a top-level field in all JSON log lines. To trace a specific request: CloudWatch Logs Insights → filter `traceId = "<id>"`. The CloudWatch dashboard shows redirect latency percentiles, cache hit rate, URL creation/error counts, and RDS CPU in a single view.

**Database migrations:** Flyway runs automatically at application startup. Add `V{n}__description.sql` to `db/migration/`, merge to `main`, and the next deploy applies the migration before the new application version starts serving traffic.
