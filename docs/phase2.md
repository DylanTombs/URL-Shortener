# Phase 2 — Infrastructure Hardening

## Goal
Wire application-level features that underpin the AWS deployment:
read replica routing, distributed rate limiting, click tracking, and a
complete ECS task definition with secrets injection.

---

## 2.1 Dynamic Base URL

**Problem:** `UrlController` has `https://sho.rt` hardcoded. Must be injected per environment.

**Fix:** Add `@Value("${app.base-url}") private String baseUrl;` to `UrlController`.
`application.yml` already has `app.base-url: ${APP_BASE_URL:https://sho.rt}`.
ECS task definition passes `APP_BASE_URL` from SSM Parameter Store.

**Files:** `src/main/java/com/urlshortener/controller/UrlController.java`

---

## 2.2 Click Count Increment

**Problem:** `click_count` column is never updated. Stats endpoint always returns 0.

**Approach:** Synchronous increment via `@Modifying` query in `UrlRepository`:

```java
@Modifying
@Transactional
@Query("UPDATE ShortenedUrl u SET u.clickCount = u.clickCount + 1 WHERE u.code = :code")
void incrementClickCount(@Param("code") String code);
```

Called inside `UrlService.resolveUrl()` after confirming the URL is valid and not expired,
before returning. This runs on the **primary** DataSource (write operation).

**Tradeoff vs async:** Async increment (via message queue or scheduled batch) reduces
redirect latency and survives DB write spikes, but adds complexity and loses at-least-once
semantics without extra infra. At current scale, synchronous is correct. At 10x scale,
revisit with SQS + Lambda batch counter.

**Files:**
- `src/main/java/com/urlshortener/repository/UrlRepository.java`
- `src/main/java/com/urlshortener/service/UrlService.java`

---

## 2.3 Bucket4j Distributed Rate Limiting

**Problem:** `RateLimitConfig.java` is an empty placeholder. WAF handles the first line
of defence (100 req/5min per IP), but application-level limiting provides a second layer
that can distinguish endpoints and return structured error responses.

**Architecture:**
- `Bucket4j` `ProxyManager` backed by Spring Cache (Redis) — no in-memory state,
  works correctly across multiple ECS tasks
- `RateLimitInterceptor` implements `HandlerInterceptor`; checks bucket before handler
- Per-IP limits:
  - `GET /{code}` — 60 requests/minute
  - `POST /api/v1/urls` — 10 requests/minute
- On limit exceeded: HTTP 429 with `Retry-After: <seconds>` header and structured JSON body:
  ```json
  {"error": "RATE_LIMIT_EXCEEDED", "message": "Too many requests. Try again in X seconds."}
  ```

**Key classes:**

### `RateLimitConfig.java`
Registers `ProxyManager<String>` bean using `JCacheProxyManager` wired to the
`cacheManager` bean (Redis-backed). Creates two `BandwidthConfig` beans — one for
redirect rate, one for create rate.

```java
@Bean
public ProxyManager<String> bucketProxyManager(CacheManager cacheManager) {
    return Bucket4j.extension(JCache.class).proxyManagerForCache(
        cacheManager.getCache("rate-limit")
    );
}
```

### `RateLimitInterceptor.java` (new — `config/` package)
Implements `HandlerInterceptor`. Extracts real client IP from `X-Forwarded-For`
(ALB always sets this). Probes the appropriate bucket for the matched route.

```java
String clientIp = Optional.ofNullable(request.getHeader("X-Forwarded-For"))
    .map(xff -> xff.split(",")[0].trim())
    .orElse(request.getRemoteAddr());

String bucketKey = clientIp + ":" + routeKey;
Bucket bucket = proxyManager.builder().build(bucketKey, () -> createBandwidth(routeKey));
if (!bucket.tryConsume(1)) {
    response.setStatus(429);
    response.setHeader("Retry-After", "60");
    // write ErrorResponse JSON body
    return false;
}
```

### `WebMvcConfig.java` (new — `config/` package)
Registers `RateLimitInterceptor` via `addInterceptors()`. Applies to all routes
except `/actuator/**`.

**Bucket configuration:**
```java
BandwidthConfig redirectBandwidth = BandwidthConfig.simple(60, Duration.ofMinutes(1));
BandwidthConfig createBandwidth   = BandwidthConfig.simple(10, Duration.ofMinutes(1));
```

**Redis cache config addition** (`RedisConfig.java`):
Add `"rate-limit"` cache with 2-minute TTL to the per-cache configuration map.

**Test:**
- `RateLimitInterceptorTest` — unit test with mock `ProxyManager`; verify 200 for first N
  requests and 429 with `Retry-After` on exhaustion
- `UrlControllerIT` — extend with test firing 61 consecutive redirect requests,
  asserting 61st returns HTTP 429

**Files:**
- `src/main/java/com/urlshortener/config/RateLimitConfig.java`
- `src/main/java/com/urlshortener/config/RateLimitInterceptor.java` (new)
- `src/main/java/com/urlshortener/config/WebMvcConfig.java` (new)
- `src/test/java/com/urlshortener/unit/RateLimitInterceptorTest.java` (new)

---

## 2.4 Read Replica Routing

**Problem:** All database reads go to the primary. The read replica defined in Terraform
is wired in AWS but not used by the application.

### Architecture

```
Write path  → @Transactional                → PRIMARY DataSource (HikariPool → RDS primary)
Read path   → @Transactional(readOnly=true) → REPLICA DataSource (HikariPool → RDS read replica)
```

### Implementation Steps

**Step 1 — `DataSourceType` enum:**
```java
public enum DataSourceType { PRIMARY, REPLICA }
```

**Step 2 — `DataSourceContextHolder`:**
Thread-local holder that the transaction interceptor populates before a connection is acquired.
```java
public class DataSourceContextHolder {
    private static final ThreadLocal<DataSourceType> CONTEXT =
        ThreadLocal.withInitial(() -> DataSourceType.PRIMARY);

    public static void set(DataSourceType type) { CONTEXT.set(type); }
    public static DataSourceType get()          { return CONTEXT.get(); }
    public static void clear()                  { CONTEXT.remove(); }
}
```

**Step 3 — `ReadWriteRoutingDataSource`:**
Extends `AbstractRoutingDataSource`. Key lookup delegates to the thread-local.
```java
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.get();
    }
}
```

**Step 4 — `DataSourceConfig` (`@Configuration`):**
- Creates `HikariDataSource` primary and replica beans from `spring.datasource.*` and
  `spring.datasource.replica.*` properties
- Creates `ReadWriteRoutingDataSource` with `PRIMARY` as default lookup key
- Wraps it in `LazyConnectionDataSourceProxy` — **critical**: this defers connection
  acquisition until the first SQL statement, allowing the transaction read-only flag to
  be set before a physical connection is chosen
- Marks the lazy proxy as `@Primary`
- Sets `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)` on the
  main application class to prevent Spring Boot from auto-creating a conflicting DataSource

**Step 5 — `application.yml` addition:**
```yaml
spring:
  datasource:
    replica:
      url: ${DB_REPLICA_URL:jdbc:postgresql://localhost:5432/urlshortener}
      username: ${DB_REPLICA_USERNAME:${spring.datasource.username}}
      password: ${DB_REPLICA_PASSWORD:${spring.datasource.password}}
      hikari:
        pool-name: HikariReplica
        maximum-pool-size: 10
        minimum-idle: 2
```

**Step 6 — `UrlService` transaction annotations:**
```java
@Transactional(readOnly = true)
public String resolveUrl(String code) { ... }

@Transactional(readOnly = true)
public StatsResponse getStats(String code) { ... }

@Transactional   // write — uses primary
public ShortenResponse shorten(ShortenRequest request) { ... }
```

### Architectural Challenge: Transaction-DataSource Ordering

Spring's `@Transactional(readOnly=true)` hint must be read **before** a connection is
obtained. If `ReadWriteRoutingDataSource` is wired directly, Hibernate eagerly acquires
a connection before AOP processes the `readOnly` flag, so all requests land on `PRIMARY`.

**Solution:** Wrap `ReadWriteRoutingDataSource` in `LazyConnectionDataSourceProxy`.
This defers physical connection acquisition to the first SQL statement, by which point
the transaction interceptor has already set `DataSourceContextHolder`.

```
AOP → sets DataSourceContextHolder.REPLICA
   → opens Hibernate session (no connection yet)
   → first SQL statement fires
   → LazyConnectionDataSourceProxy acquires connection
   → ReadWriteRoutingDataSource reads thread-local → REPLICA ✓
```

**Files:**
- `src/main/java/com/urlshortener/config/DataSourceType.java` (new)
- `src/main/java/com/urlshortener/config/DataSourceContextHolder.java` (new)
- `src/main/java/com/urlshortener/config/ReadWriteRoutingDataSource.java` (new)
- `src/main/java/com/urlshortener/config/DataSourceConfig.java` (new)
- `src/main/java/com/urlshortener/service/UrlService.java`
- `src/main/java/com/urlshortener/UrlShortenerApplication.java` (exclude DataSourceAutoConfiguration)
- `src/main/resources/application.yml`

---

## 2.5 Terraform ECS Completion

**Current gap:** ECS module has ECR repository, log group, and IAM roles but NO task
definition, ECS service, or auto-scaling policy.

### Task Definition

```hcl
resource "aws_ecs_task_definition" "app" {
  family                   = "${var.app_name}-${var.environment}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.ecs_execution_role.arn
  task_role_arn            = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([{
    name  = var.app_name
    image = "${aws_ecr_repository.app.repository_url}:latest"
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment }
    ]
    secrets = [
      { name = "SPRING_DATASOURCE_URL",      valueFrom = "${aws_secretsmanager_secret.db.arn}:url::" },
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.db.arn}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" },
      { name = "DB_REPLICA_URL",             valueFrom = "${aws_secretsmanager_secret.db.arn}:replica_url::" },
      { name = "SPRING_DATA_REDIS_HOST",     valueFrom = aws_ssm_parameter.redis_host.arn },
      { name = "APP_BASE_URL",               valueFrom = aws_ssm_parameter.app_base_url.arn }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])
}
```

### ECS Service with ALB Attachment

```hcl
resource "aws_ecs_service" "app" {
  name            = var.app_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.ecs_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = var.app_name
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}
```

### Auto-Scaling (Target Tracking, CPU 60%)

```hcl
resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = var.max_capacity
  min_capacity       = var.min_capacity
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.app_name}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 60.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
```

### SSM Parameters Required

| Parameter | Value |
|-----------|-------|
| `/url-shortener/{env}/redis-host` | ElastiCache primary endpoint |
| `/url-shortener/{env}/app-base-url` | Public domain (e.g. `https://sho.rt`) |

### IAM Task Role Additions

The ECS task role (`aws_iam_role.ecs_task_role`) needs:
- `secretsmanager:GetSecretValue` on the DB secret ARN
- `ssm:GetParameters` on the SSM parameter ARNs
- `cloudwatch:PutMetricData` on namespace `UrlShortener` (Phase 4 prereq — add now)

**Files:**
- `terraform/modules/ecs/main.tf`
- `terraform/modules/ecs/variables.tf`
- `terraform/modules/ecs/outputs.tf`
- `terraform/environments/dev/main.tf`
- `terraform/environments/prod/main.tf`

---

## 2.6 Testing Additions

| Test | Type | Validates |
|------|------|-----------|
| `RateLimitInterceptorTest` | Unit | Bucket consumed; 429 returned on exhaustion; `Retry-After` header present |
| `ReadReplicaRoutingTest` | Unit | `DataSourceContextHolder` set correctly per transaction type |
| `UrlControllerIT` (extend) | Integration | `click_count` increments on redirect; rate limit fires at 61st request |

---

## Verification

- `mvn verify` passes — all tests, 80%+ line coverage
- Redirect logs include `"dataSource":"replica"` (add log statement to `DataSourceConfig`)
- 61st redirect within 1 minute returns HTTP 429 with `Retry-After: 60` header
- `terraform validate` passes on both dev and prod environments
- `terraform plan` on dev shows new task definition, ECS service, and scaling policy
