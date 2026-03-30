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
│   ├── controller/     UrlController.java, GlobalExceptionHandler.java
│   ├── service/        UrlService.java, CodeGenerator.java, Base62CodeGenerator.java
│   ├── repository/     UrlRepository.java
│   ├── model/          ShortenedUrl.java
│   ├── dto/            ShortenRequest.java, ShortenResponse.java, StatsResponse.java, ErrorResponse.java
│   ├── exception/      UrlNotFoundException.java, UrlExpiredException.java
│   └── config/         RedisConfig.java, RateLimitConfig.java, DataSourceConfig.java,
│                       RateLimitInterceptor.java, WebMvcConfig.java,
│                       ObservabilityConfig.java, MdcRequestIdFilter.java
├── src/test/
│   ├── unit/           CodeGeneratorTest.java, UrlServiceTest.java, RateLimitInterceptorTest.java
│   └── integration/    UrlControllerIT.java
├── db/migration/       V1__create_shortened_urls.sql
├── terraform/
│   ├── modules/        vpc/, ecs/, rds/, elasticache/, alb/, waf/, cloudwatch/, github-oidc/
│   ├── environments/   dev/, prod/
│   └── main.tf
├── .github/workflows/  ci.yml, cd.yml
├── docs/               phase2.md, phase3.md, phase4.md, phase5.md
├── k6/                 smoke.js, load.js, spike.js
└── Dockerfile
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

## Phase Tracker & Todo List

### Phase 1 — Core Service ✅ COMPLETE
All endpoints, unit + integration tests (Testcontainers), JaCoCo 80% gate.

### Phase 2 — Infrastructure Hardening (Current Phase)
- [ ] Fix hardcoded base URL in `UrlController` → inject `@Value("${app.base-url}")`
- [ ] Implement `click_count` increment via `@Modifying` query in `UrlRepository`
- [ ] Implement Bucket4j rate limiting in `RateLimitConfig` + new `RateLimitInterceptor`
- [ ] Implement read replica routing via `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy`
- [ ] Complete ECS task definition in Terraform with Secrets Manager injection
- [ ] Add ECS auto-scaling policy (CPU target tracking at 60%)
- [ ] Write tests: `RateLimitInterceptorTest`, extend `UrlControllerIT` (click count + rate limit)
- [ ] See `docs/phase2.md` for full implementation details

### Phase 3 — CI/CD Pipeline
- [ ] Write multi-stage `Dockerfile` (Maven build + JRE21 alpine runtime, non-root user)
- [ ] Create `.github/workflows/ci.yml` (build → test → ECR push → Trivy scan → deploy-dev)
- [ ] Create `.github/workflows/cd.yml` (ECS rolling deploy to prod, manual approval gate)
- [ ] Add `terraform/modules/github-oidc/` — OIDC provider + IAM role (no long-lived keys)
- [ ] Configure required GitHub Secrets (AWS_ROLE_ARN_DEV, ECR_REGISTRY, etc.)
- [ ] Create `production` GitHub environment with required reviewer
- [ ] See `docs/phase3.md` for full pipeline design

### Phase 4 — Observability
- [ ] Create `MdcRequestIdFilter` — per-request trace ID in structured logs
- [ ] Create `logback-spring.xml` — JSON log format with MDC fields as top-level keys
- [ ] Create `ObservabilityConfig` — CloudWatch Micrometer registry + environment tag
- [ ] Instrument `UrlService` with 4 custom metrics: `url.created`, `url.redirect` timer, `url.not_found`, `url.expired`
- [ ] Create `terraform/modules/cloudwatch/` — 4 alarms + 6-widget dashboard
- [ ] Wire cloudwatch module into dev and prod environment Terraform
- [ ] See `docs/phase4.md` for full observability design

### Phase 5 — Polish and Documentation
- [ ] Write `k6/smoke.js` (5 VUs, 1 min), `k6/load.js` (50 VUs, 10 min), `k6/spike.js` (500 VUs burst)
- [ ] Write `ARCHITECTURE.md` (9 sections: overview, diagram, request flows, caching, DB, network, security, ops)
- [ ] Write `DECISIONS.md` (7 decisions with context/options/consequences/at-10x format)
- [ ] Write `RUNBOOK.md` (8 operational scenarios, all commands copy-pasteable)
- [ ] Update `README.md` — local setup under 10 minutes from fresh clone
- [ ] Run all k6 tests; confirm all thresholds pass
- [ ] See `docs/phase5.md` for full verification checklist

---

## Interview Justification Points
Every decision above has a "why" that can be defended in an Amazon SWE interview.
When asked about any component, the answer should cover:
1. What problem it solves
2. What alternatives were considered
3. Why this choice was made
4. What the tradeoffs are
5. What you would do differently at 10x scale
