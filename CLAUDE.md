# URL Shortener — Current Phase: 2

## Project Goal
Production-grade URL shortener built to senior Amazon SWE standards.
Not a tutorial project. Every decision is documented and justifiable in an interview.

---

## Stack
- Java 21, Spring Boot 3.x, Maven
- PostgreSQL (primary + read replica)
- Redis (Spring Cache via @Cacheable)
- AWS: ECS, RDS, ElastiCache, ALB, WAF
- Terraform (all infrastructure — nothing created manually in console)
- GitHub Actions (CI and CD as separate workflows)
- Testcontainers (real Postgres + Redis in integration tests, no H2)
- k6 (load testing)
- CloudWatch (structured JSON logs + custom metrics + dashboards)

---

## Project Structure
```
url-shortener/
├── src/main/java/com/urlshortener/
│   ├── controller/     UrlController.java
│   ├── service/        UrlService.java, CodeGenerator.java
│   ├── repository/     UrlRepository.java
│   ├── model/          ShortenedUrl.java
│   ├── dto/            ShortenRequest.java, ShortenResponse.java
│   ├── exception/      UrlNotFoundException.java, UrlExpiredException.java
│   └── config/         RedisConfig.java, RateLimitConfig.java
├── src/test/
│   ├── unit/           CodeGeneratorTest.java, UrlServiceTest.java
│   └── integration/    UrlControllerIntegrationTest.java
├── terraform/
│   ├── modules/        vpc/, ecs/, rds/, elasticache/, alb/, waf/
│   ├── environments/   dev/, prod/
│   └── main.tf
└── .github/workflows/  ci.yml, cd.yml
```

---

## API Contract
```
POST /api/v1/urls
Body: { "url": "https://...", "ttlDays": 30 }   (ttlDays optional)
201: { "code": "aB3xK9mQ", "shortUrl": "https://sho.rt/aB3xK9mQ", "expiresAt": "..." }
422: { "error": "INVALID_URL", "message": "..." }

GET /{code}
301: Location: https://original-url.com
404: { "error": "NOT_FOUND" }
410: { "error": "EXPIRED" }

GET /api/v1/urls/{code}/stats
200: { "code": "aB3xK9mQ", "clickCount": 142, "createdAt": "..." }

GET /actuator/health
200: { "status": "UP", "redis": "UP", "db": "UP" }
```

API is versioned at /api/v1/ from day one. Non-negotiable — APIs are contracts.

---

## Database Schema
```sql
CREATE TABLE shortened_urls (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(12) UNIQUE NOT NULL,
    long_url    TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ,
    click_count BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_shortened_urls_code ON shortened_urls(code);
CREATE INDEX idx_shortened_urls_expires_at ON shortened_urls(expires_at)
    WHERE expires_at IS NOT NULL;
```

Partial index on expires_at — only rows that actually expire need it.

---

## Key Decisions (already made — do not revisit without strong reason)

### ID Generation
- Base62 encoding (a-z, A-Z, 0-9) of a random 62-bit long
- Produces ~8 character codes
- 62^8 possible codes — collisions astronomically rare, handled with retry loop
- NOT UUID (too long, ugly), NOT sequential integers (enumerable, attackable)
- Documented in DECISIONS.md

### Caching Strategy
- Cache ONLY the redirect path (GET /{code}) — never the write path
- Cache miss: fetch from read replica, write to Redis with TTL
- TTL = min(24 hours, time until link expiry) — never serve stale expired links
- Spring @Cacheable backed by Redis

### Database Architecture
- Read replica for all GET /{code} reads — reads outnumber writes by orders of magnitude
- Primary for all writes
- This is not premature optimisation — it is the correct architecture for a read-heavy workload

### Network Layout
- ECS tasks, RDS, ElastiCache — all private subnets
- Only ALB in public subnets
- NAT gateway for ECR pulls and CloudWatch metrics
- WAF rate limit: 100 requests per 5 minutes per IP (first line of defence against enumeration)

### Terraform State
- Remote state in S3 with DynamoDB table for locking
- Never commit terraform.tfstate

---

## Testing Standards
- Unit tests: CodeGenerator (collision probability, charset, length), UrlService (valid URLs, invalid URLs, expired TTL logic)
- Integration tests: Testcontainers — real Postgres and Redis in Docker, NOT H2
- 80%+ coverage requirement

---

## Observability Requirements
- All logs are structured JSON (not string concatenation)
- Example: {"timestamp":"...","level":"INFO","traceId":"abc123","code":"aB3xK9mQ","event":"redirect_attempt","cacheHit":true,"latencyMs":3}
- CloudWatch custom metrics: redirect.latency (p50/p95/p99), cache.hitRate, url.created, url.notFound, db.queryLatency
- Alarms: p99 > 500ms, cache hit rate < 70%, RDS CPU > 80%, 5xx > 1%
- CloudWatch dashboard with all metrics visible at once

---

## Required Documentation (non-negotiable for senior-quality standard)
- ARCHITECTURE.md — every non-obvious decision explained
- DECISIONS.md — context → options → decision → consequences for each key choice
- RUNBOOK.md — how to deploy, roll back, investigate a latency spike

---

## Phase Tracker
- [ ] Phase 1 — Core service (Spring Boot, two endpoints, tests)
- [ ] Phase 2 — Infrastructure as code (Terraform modules)
- [ ] Phase 3 — CI/CD pipeline (GitHub Actions)
- [ ] Phase 4 — Observability (structured logs, CloudWatch metrics, alarms, dashboard)
- [ ] Phase 5 — Polish and documentation (load test with k6, all docs complete)

---

## Interview Justification Points
Every decision above has a "why" that can be defended in an Amazon SWE interview.
When asked about any component, the answer should cover:
1. What problem it solves
2. What alternatives were considered
3. Why this choice was made
4. What the tradeoffs are
5. What you would do differently at 10x scale
