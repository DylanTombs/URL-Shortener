# Architecture Decision Records

---

## Decision 1: Base62 Random Codes vs UUID vs Sequential Integers

**Date:** 2026-03-01
**Status:** Accepted

### Context
Short codes are the core product. They appear in URLs sent to end users, so they must be short, URL-safe, and non-guessable. The generation strategy determines the code length, the attack surface, and the collision probability at scale.

### Options Considered

1. **UUID (v4)** — Globally unique, zero collision risk. 36 characters including hyphens (e.g. `550e8400-e29b-41d4-a716-446655440000`). Unusable as a short URL — the whole point of the service is to produce a short string.

2. **Sequential integers** — Compact and collision-free. However, sequential IDs are enumerable. An attacker issuing `GET /1`, `GET /2`, `GET /3` can trivially crawl the entire link database and scrape every destination URL. Unacceptable for a public service.

3. **Base62 random (chosen)** — Encode a random 62-bit long (from `SecureRandom`) using the Base62 alphabet (a–z, A–Z, 0–9). Produces an 8-character code. 62^8 ≈ 218 trillion possible values. Collision probability at 1 billion URLs ≈ 0.0023%. A retry loop handles collisions; in practice they never occur.

### Decision
Base62 encoding of a cryptographically random 62-bit long. 8 characters, URL-safe alphabet, non-enumerable, astronomically low collision probability.

### Consequences
- ✅ Codes are short and human-readable in a URL
- ✅ Non-enumerable — crawling the keyspace is computationally infeasible
- ✅ No coordination required — no sequence, no counter, no distributed ID generator
- ⚠️ Collision handling adds a retry loop (up to 5 attempts). At current scale this is dead code in practice.

### At 10x Scale
At 10 billion stored URLs, collision probability per insert is ~2.3%. The retry loop becomes observable in latency. Options: increase code length to 10 characters (62^10 ≈ 839 trillion), or switch to a hybrid — a 4-character random prefix on top of a base-36 timestamp suffix.

---

## Decision 2: Cache Only the Redirect Path

**Date:** 2026-03-01
**Status:** Accepted

### Context
Redis caching reduces latency and shields the database from read traffic. The question is: which operations should be cached?

### Options Considered

1. **Cache everything** — Cache both `POST /api/v1/urls` responses and `GET /{code}` responses. The write path is called once per URL creation; caching a result that is never re-read wastes memory and adds complexity for zero benefit.

2. **Cache nothing** — Every redirect hits the database. At 1000:1 read:write ratio this means the RDS replica absorbs millions of reads per day that Redis could serve in microseconds.

3. **Cache only `GET /{code}` (chosen)** — The redirect path is the hot path. The same code is clicked many times after creation. A Redis hit for a warm code takes ~0.5ms vs ~5–15ms for a RDS replica read. Cache miss on first click, cache hit on all subsequent clicks.

### Decision
Cache only the `GET /{code}` → `longUrl` mapping. Never cache the write path or stats path.

### Consequences
- ✅ Hot redirects served in sub-millisecond time from Redis
- ✅ Database read traffic reduced to near-zero for popular links
- ✅ Simple invalidation model — entries expire by TTL, no explicit invalidation needed
- ⚠️ First click after a cache miss (or after TTL expiry) hits the database — visible in the `cache_hit=false` latency percentile

### At 10x Scale
At extreme read volume, a single Redis node becomes the bottleneck. Move to Redis Cluster with consistent hashing. The `cache_hit` metric tag on `url.redirect` makes it straightforward to observe hit rate per node.

---

## Decision 3: Read Replica for All GET Reads

**Date:** 2026-03-01
**Status:** Accepted

### Context
URL shorteners are read-heavy by nature. After creation, a link may be clicked thousands of times. Every click that misses the Redis cache becomes a database read. Without a read replica, all traffic — reads and writes — competes on the same RDS instance.

### Options Considered

1. **Single RDS instance (primary only)** — Simpler to operate. At low scale this is fine. Under a sustained read spike (viral link), the primary's CPU and connection count climb and writes (click count increments) compete with reads.

2. **Read replica (chosen)** — All `GET /{code}` and `GET /stats` queries route to the replica. All writes route to the primary. The replica can be independently scaled (upgraded to a larger instance class) without affecting the primary.

3. **Aurora PostgreSQL** — Automatically managed read replicas, up to 15 readers, sub-10ms replica lag. More capable but significantly more expensive and adds operational complexity not justified at current scale.

### Decision
Single RDS read replica for all reads. Routing via `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy` in Spring. The `readOnly` flag on `@Transactional` determines which data source is selected.

### Consequences
- ✅ Primary stays free for writes even under read spikes
- ✅ Replica can be resized or failover-promoted independently
- ⚠️ Replica lag (typically < 10ms on RDS) means a redirect issued immediately after URL creation could theoretically miss on the replica. In practice, the cache miss path writes to Redis, so the second request is a cache hit regardless.
- ⚠️ `LazyConnectionDataSourceProxy` is a non-obvious requirement. Without it, Hibernate acquires a connection before the `readOnly` flag is set, and all traffic routes to the primary. See ARCHITECTURE.md §6.

### At 10x Scale
Promote to Aurora PostgreSQL with a read cluster. Add PgBouncer in the ECS task definition as a sidecar container to pool connections — at high task counts, each HikariCP pool creates direct Postgres connections, and Aurora's max_connections limit becomes a bottleneck.

---

## Decision 4: Two-Layer Rate Limiting (WAF + Bucket4j)

**Date:** 2026-03-02
**Status:** Accepted

### Context
URL shorteners are a target for enumeration attacks (iterating codes to harvest destination URLs) and abuse (creating large volumes of spam links). Rate limiting is required. The question is where to enforce it.

### Options Considered

1. **WAF only** — AWS Managed Rules + rate limit rule at the ALB. Simple to configure. However, WAF enforces a single rate limit across all paths and cannot apply different limits to different endpoints (e.g. stricter limits on the create endpoint). WAF also returns a plain HTTP 403, not a structured JSON 429.

2. **Application-level only (Bucket4j)** — Can distinguish endpoints, return structured JSON responses with `Retry-After`, and use Redis for distributed state. However, every abusive request still passes through the ALB and consumes ECS CPU before being rejected.

3. **WAF + Bucket4j (chosen)** — WAF acts as the network-layer first line of defence (100 req / 5 min per IP) and blocks volumetric attacks before they reach ECS. Bucket4j acts as the application-layer second line of defence with per-endpoint limits (60 req/min for redirects, 10 req/min for creates) and structured 429 responses.

### Decision
Both layers, complementary not redundant. WAF stops abuse early. Bucket4j provides fine-grained control and observable, user-friendly responses.

### Consequences
- ✅ Volumetric attacks stopped at the network edge before consuming compute
- ✅ Per-endpoint limits enforced consistently across all ECS replicas (state in Redis)
- ✅ 429 responses include `Retry-After` header — compliant with RFC 6585
- ⚠️ Redis becomes a dependency of the rate limiter. If Redis is unavailable, `RateLimitInterceptor` fails open (allows the request) to avoid turning a cache failure into a service outage.

### At 10x Scale
Move WAF to a global CloudFront distribution so rate limiting happens at the CDN edge, not just at the ALB in a single region. Redirect responses become cacheable at CloudFront, eliminating most origin hits entirely.

---

## Decision 5: Testcontainers (Real Postgres + Redis) vs H2 In-Memory

**Date:** 2026-03-02
**Status:** Accepted

### Context
Integration tests need a database and cache. The options are an in-memory H2 database that approximates Postgres, or real Postgres and Redis via Testcontainers.

### Options Considered

1. **H2 in Postgres compatibility mode** — Fast, no Docker dependency. However, H2 diverges from real Postgres on constraint enforcement, index types (`CREATE INDEX CONCURRENTLY` is not supported), `TIMESTAMPTZ` handling, and some SQL syntax. Tests pass; production fails. This is a known failure mode documented in multiple incident reports across the industry.

2. **Testcontainers with real Postgres and Redis (chosen)** — Pulls official Docker images and starts containers inline with the test JVM. Tests run against the exact same engine version (`postgres:16-alpine`, `redis:7-alpine`) as production. Eliminates the dialect gap entirely.

### Decision
Testcontainers. The extra startup time (~5–10 seconds for container pull on first run, ~2–3 seconds on cached runs) is justified by test fidelity. A false-passing integration test is worse than a slow-passing one.

### Consequences
- ✅ Integration tests are faithful — what passes locally passes in CI and in production
- ✅ The `partial index` on `expires_at` and the `@Modifying` JPQL query are tested against real Postgres
- ⚠️ Requires Docker to be running locally and in CI. `ubuntu-latest` GitHub Actions runners have Docker pre-installed — no extra configuration needed.

### At 10x Scale
No change needed at the test level. If test suite duration becomes a bottleneck, run integration tests in parallel using JUnit 5's `@TestMethodOrder` and separate Testcontainer instances per class.

---

## Decision 6: ECS Fargate vs EC2 vs Lambda

**Date:** 2026-03-01
**Status:** Accepted

### Context
The service needs to run containerised Java. The deployment target affects operational burden, cost model, latency characteristics, and scaling behaviour.

### Options Considered

1. **AWS Lambda** — Zero server management, scales to zero, pay-per-invocation. However, Lambda cold starts add 200–500ms of JVM initialisation latency (even with SnapStart) on the first request to a new container. For a redirect service where the user is waiting synchronously, an intermittent 500ms penalty is a direct SLA violation.

2. **ECS on EC2** — Full control over instance types. Requires capacity planning, AMI patching, autoscaling group management, and bin-packing tasks onto instances. The operational overhead does not deliver meaningful cost savings at this scale.

3. **ECS Fargate (chosen)** — No server management. Tasks are sized by CPU and memory directly. Scaling is per-task (no over-provisioned EC2 capacity). Integrates natively with ALB, Secrets Manager, CloudWatch, and ECR — all already in use.

### Decision
ECS Fargate. The operational simplicity is the correct tradeoff at current and projected scale.

### Consequences
- ✅ No AMI patching, no capacity planning, no bin-packing
- ✅ Per-task pricing — no idle EC2 instances
- ✅ Native integration with the rest of the AWS stack
- ⚠️ Fargate task startup is slower than Lambda (30–60 seconds vs 200ms). This is acceptable — ECS scales out ahead of demand via target tracking, not in response to a single request.

### At 10x Scale
ECS Fargate continues to work. Cost optimisation: move non-latency-sensitive background jobs (e.g. expired-URL cleanup) to Lambda. Keep the redirect path on Fargate. Consider Fargate Spot for dev/staging environments (up to 70% cost reduction; interruptions acceptable in non-production).

---

## Decision 7: Synchronous Click Count Increment

**Date:** 2026-03-02
**Status:** Accepted

### Context
Every redirect should increment the `click_count` on the `shortened_urls` row. This is a write on the RDS primary that happens inline with the redirect response.

### Options Considered

1. **Synchronous increment (chosen)** — `urlRepository.incrementClickCount(code)` called in the controller immediately after `resolveUrl()` returns. Simple, consistent, no message queue infrastructure required.

2. **Asynchronous via SQS + batch consumer** — Controller puts a message on SQS; a separate Lambda or ECS worker consumes in batches and issues bulk `UPDATE`. Decouples redirect latency from write latency entirely. At extreme scale (>100k redirects/s), this is the correct architecture — synchronous increments at that rate would saturate the primary.

3. **In-memory batch + periodic flush** — Aggregate click counts in a `ConcurrentHashMap` in each ECS task, flush to DB every 30 seconds. Loses up to 30 seconds of click data on task restart/crash. Operationally fragile.

### Decision
Synchronous increment at current scale. The `@Modifying` query is a single indexed update on the primary; at the expected traffic volumes it adds < 2ms to the redirect path (measured via the `url.redirect` timer).

### Consequences
- ✅ Exact click counts — no eventual consistency, no data loss on task restart
- ✅ No additional infrastructure (no SQS, no consumer service)
- ⚠️ Every redirect that misses the cache causes two DB operations: read from replica + write to primary. This is observable in the `cache_hit=false` latency distribution.

### At 10x Scale
Move to SQS-based async increment when `click_count` writes become a measurable fraction of redirect latency (expected threshold: >100k req/s sustained). The counter column can be backfilled from SQS events; no schema change required.
