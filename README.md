# URL Shortener

Production-grade URL shortener built to senior Amazon SWE standards. Fully deployable on AWS with infrastructure as code, CI/CD, observability, and load testing.

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.4 |
| Database | PostgreSQL 16 (primary + read replica) |
| Cache | Redis 7 via Spring Cache (`@Cacheable`) |
| Rate limiting | Bucket4j 8.x token-bucket, Redis-backed |
| Infrastructure | AWS ECS Fargate, RDS, ElastiCache, ALB, WAF |
| IaC | Terraform (all infra — nothing manual in console) |
| CI/CD | GitHub Actions (CI + CD as separate workflows) |
| Load testing | k6 (smoke, load, spike) |
| Observability | CloudWatch structured JSON logs, custom metrics, alarms, dashboard |

---

## Quick Start

**Prerequisites:** Java 21, Maven, Docker

```bash
# Unit tests only (no Docker required)
mvn test

# Full suite including integration tests (requires Docker)
mvn verify
```

**Local development:**

```bash
docker run -d -p 5432:5432 -e POSTGRES_DB=urlshortener -e POSTGRES_USER=urlshortener -e POSTGRES_PASSWORD=secret postgres:16-alpine
docker run -d -p 6379:6379 redis:7-alpine

mvn spring-boot:run
```

---

## API

```
POST /api/v1/urls
Body:  { "url": "https://example.com/long/path", "ttlDays": 30 }
201:   { "code": "aB3xK9mQ", "shortUrl": "https://sho.rt/aB3xK9mQ", "expiresAt": "..." }
422:   { "error": "INVALID_URL", "message": "..." }

GET /{code}
301:   Location: https://example.com/long/path
404:   { "error": "NOT_FOUND" }
410:   { "error": "EXPIRED" }
429:   { "error": "RATE_LIMIT_EXCEEDED", "message": "..." }  +  Retry-After header

GET /api/v1/urls/{code}/stats
200:   { "code": "aB3xK9mQ", "clickCount": 142, "createdAt": "..." }

GET /actuator/health
200:   { "status": "UP" }
```

---

## Architecture

ECS tasks, RDS, and ElastiCache run in private subnets. Only the ALB is public. WAF sits in front of the ALB as the first rate-limit defence (100 req/5 min per IP). Application-level rate limiting (Bucket4j, Redis-backed) provides a second layer per endpoint.

All reads route to the RDS read replica via `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy`. All writes route to the primary. Hot redirects are served from Redis, cutting DB load to near zero.

Secrets Manager holds DB credentials. SSM Parameter Store holds non-secret config (Redis host, base URL). No plaintext environment variables in the ECS task definition.

```
Internet → WAF → ALB (public subnets)
                  ↓
            ECS Fargate tasks (private subnets)
            ├── Redis cache (ElastiCache)
            ├── RDS primary  (writes)
            └── RDS replica  (reads)
```

---

## Key Design Decisions

**Code generation** — Base62 encoding of a random 62-bit long. Produces 8-character codes (62^8 ≈ 218 trillion values). Collision probability is astronomically low; a retry loop handles it anyway. Not UUID (too long, ugly) and not sequential integers (enumerable, attackable).

**Cache strategy** — Only the redirect path is cached. Write path is never cached (cache-aside on reads only). TTL = min(24h, time-to-expiry) so a cached entry always expires before the link does — stale expired links are never served from Redis.

**Read replica routing** — `LazyConnectionDataSourceProxy` is required. Without it, Hibernate acquires a physical connection before Spring's AOP processes `@Transactional(readOnly=true)`, so every request lands on the primary. The lazy proxy defers connection acquisition to the first SQL statement, by which point the routing key is correct.

**Click count increment** — Incremented in the controller after `resolveUrl()` returns, not inside the `@Cacheable` method. This ensures the counter advances on every redirect, even when the URL is served from Redis and the cached method body is skipped.

**Rate limiting** — Two layers: WAF at the ALB (100 req/5 min, primary defence) and Bucket4j in the application (60 req/min for redirects, 10 req/min for creates, secondary defence). Bucket state stored in Redis so limits are enforced consistently across all ECS task replicas.

---

## Infrastructure

All infrastructure is managed by Terraform. Remote state is stored in S3 with DynamoDB locking — never commit `terraform.tfstate`.

```bash
cd terraform/environments/dev
terraform init
terraform plan
terraform apply
```

Two environments: `dev` (single NAT gateway, t3.micro instances, 1–4 tasks) and `prod` (multi-AZ, HA NAT, t3.small instances, 2–10 tasks with CPU auto-scaling at 60%).

---

## CI/CD

Two GitHub Actions workflows:

- **CI** (`ci.yml`) — triggered on every push: build → unit tests → integration tests → Docker build → ECR push → Trivy image scan → rolling deploy to dev
- **CD** (`cd.yml`) — triggered on release tag: rolling deploy to prod behind a manual approval gate in the `production` GitHub environment

AWS authentication uses OIDC — no long-lived keys stored in GitHub Secrets.

---

## Observability

All logs are structured JSON with a per-request trace ID:

```json
{"timestamp":"...","level":"INFO","traceId":"abc123","code":"aB3xK9mQ","event":"redirect","cacheHit":true,"latencyMs":3}
```

Custom CloudWatch metrics: `url.created`, `url.redirect` (p50/p95/p99 timer), `url.not_found`, `url.expired`.

Alarms: p99 latency > 500ms, cache hit rate < 70%, RDS CPU > 80%, 5xx error rate > 1%.

A CloudWatch dashboard shows all metrics in a single view.

---

## Load Testing

```bash
k6 run k6/smoke.js   # 5 VUs, 1 min  — baseline sanity check
k6 run k6/load.js    # 50 VUs, 10 min — sustained throughput
k6 run k6/spike.js   # 500 VUs burst  — auto-scaling validation
```

---

## Repository Layout

```
src/main/java/com/urlshortener/
├── controller/     UrlController.java, GlobalExceptionHandler.java
├── service/        UrlService.java, CodeGenerator.java, Base62CodeGenerator.java
├── repository/     UrlRepository.java
├── model/          ShortenedUrl.java
├── dto/            ShortenRequest.java, ShortenResponse.java, StatsResponse.java, ErrorResponse.java
├── exception/      UrlNotFoundException.java, UrlExpiredException.java
└── config/         RedisConfig.java, RateLimitConfig.java, RateLimitInterceptor.java,
                    WebMvcConfig.java, DataSourceConfig.java, DataSourceType.java,
                    DataSourceContextHolder.java, ReadWriteRoutingDataSource.java,
                    ObservabilityConfig.java, MdcRequestIdFilter.java

src/test/java/com/urlshortener/
├── unit/           CodeGeneratorTest.java, UrlServiceTest.java,
│                   RateLimitInterceptorTest.java, ReadReplicaRoutingTest.java
└── integration/    UrlControllerIT.java

terraform/
├── modules/        vpc/, ecs/, rds/, elasticache/, alb/, waf/, cloudwatch/, github-oidc/
└── environments/   dev/, prod/

.github/workflows/  ci.yml, cd.yml
k6/                 smoke.js, load.js, spike.js
docs/               ARCHITECTURE.md, DECISIONS.md, RUNBOOK.md
```
