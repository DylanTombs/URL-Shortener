# Phase 8 — Operational Resilience

## Goal
The architecture is sound and the code is correct. The remaining gap is resilience under
partial failure. Three critical issues would cause a Redis or DB incident to become a
service outage. Fix them, then address the highest-priority medium issues that would
surface in a senior code review or bar-raiser interview.

Issues are ordered: critical first (Redis failure handling, click durability, header
security), then medium (liveness/readiness, request size limits, query timeout).

---

## 8.1 Handle Redis Failures Gracefully (Fail Open)

**Problem:** Two code paths throw uncaught exceptions when Redis is unavailable:

1. `RateLimitInterceptor.preHandle()` calls `proxyManager.builder()...tryConsumeAndReturnRemaining()`
   — any Redis connectivity error bubbles up as a 500 to the caller.
2. `UrlService.resolveUrl()` calls `cacheManager.getCache().get()` and `redisValueOps.set()`
   — a `RedisConnectionFailureException` or `QueryTimeoutException` causes the entire
   redirect to fail with a 500.

Both paths should degrade gracefully: the rate limiter should fail open (allow the
request), and the cache should fall through to the DB.

An Amazon SDE interviewer will ask this question in every distributed systems discussion:
*"What happens when your cache layer goes down?"* The answer must never be "the service
goes down with it."

**Fix — RateLimitInterceptor:**

```java
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                          Object handler) throws Exception {
    // ... route detection (unchanged) ...
    try {
        ConsumptionProbe probe = proxyManager.builder()
                .build(bucketKey, () -> config)
                .tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.min(3600L,
                    Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests\"}");
            return false;
        }
        return true;
    } catch (Exception e) {
        log.warn("rate_limiter_unavailable clientIp={} route={} — failing open",
                clientIp, routeKey, e);
        return true; // fail open: allow the request when Redis is unreachable
    }
}
```

**Fix — UrlService.resolveUrl():**

Wrap the cache read in try-catch (treat exception as miss). Wrap the cache write in
try-catch (log and continue — redirect works, just uncached):

```java
// Cache read — fail open (treat as miss)
try {
    Cache cache = cacheManager.getCache(CACHE_NAME);
    if (cache != null) {
        Cache.ValueWrapper cached = cache.get(code);
        if (cached != null) {
            cacheHit = true;
            log.debug("url_resolved code={} cacheHit=true", code);
            return (String) cached.get();
        }
    }
} catch (Exception e) {
    log.warn("cache_read_failed code={} — falling back to DB", code, e);
    meterRegistry.counter("cache.error", "operation", "read").increment();
}

// ... DB load (unchanged) ...

// Cache write — best effort
try {
    Duration ttl = computeCacheTtl(url.getExpiresAt());
    redisValueOps.set(CACHE_KEY_PREFIX + code, url.getLongUrl(), ttl);
} catch (Exception e) {
    log.warn("cache_write_failed code={} — redirect will still serve from DB", code, e);
    meterRegistry.counter("cache.error", "operation", "write").increment();
}
```

The `cache.error` counter with an `operation` tag gives CloudWatch visibility into whether
failures are reads, writes, or both — critical for diagnosing a Redis incident.

**Tests:**
- Unit: mock `redisValueOps.set()` to throw `RedisConnectionFailureException` → verify
  `resolveUrl()` returns the long URL (not 500).
- Unit: mock `cacheManager.getCache()` to throw → verify fallback to DB.
- Unit: mock `proxyManager.builder()` to throw → verify `preHandle()` returns `true`
  (fail open).

**Files:**
- `src/main/java/com/urlshortener/config/RateLimitInterceptor.java`
- `src/main/java/com/urlshortener/service/UrlService.java`
- `src/test/java/com/urlshortener/unit/UrlServiceTest.java`
- `src/test/java/com/urlshortener/unit/RateLimitInterceptorTest.java`

---

## 8.2 Make Click Count Increment Durable

**Problem:** The controller calls `resolveUrl()` and then `incrementClickCount()` as two
separate method calls. If the ECS task crashes, is OOM-killed, or receives SIGTERM between
those two calls, the redirect succeeded (the client was redirected) but the click was never
counted. At scale this is continuous silent data loss.

This is a standard "distributed systems reliability" interview question: *"Can your system
lose data? Under what conditions?"* The current answer is yes.

**Fix — Graceful Shutdown + Best-Effort Increment:**

The simplest fix without adding infrastructure: ensure `incrementClickCount()` failures
are caught and logged at the controller level, and configure Spring Boot graceful shutdown
so in-flight requests complete before the container exits.

Step 1 — Graceful shutdown in `application.yml`:

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

This causes ECS SIGTERM to stop accepting new requests and wait up to 30 seconds for
in-flight requests to finish before the JVM exits. The ALB deregisters the task before
SIGTERM is sent (ALB deregistration delay: 30s), so no new requests arrive during drain.

Step 2 — Isolate increment failure from redirect at the controller:

```java
@GetMapping("/{code}")
public ResponseEntity<Void> redirect(@PathVariable ...) {
    String longUrl = urlService.resolveUrl(code);
    try {
        urlService.incrementClickCount(code);
    } catch (Exception e) {
        // Redirect is more important than counting. Log and continue.
        log.error("click_count_increment_failed code={}", code, e);
        meterRegistry.counter("url.click_count_error").increment();
    }
    return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, longUrl)
            .build();
}
```

This ensures a DB write failure (replica lag, primary failover) never converts a
successful redirect into a 500.

**Why not async SQS?**
Async increment via SQS is the right architecture at >100k req/s (see DECISIONS.md
Decision 7). At current scale, synchronous is correct. The graceful shutdown + isolated
catch covers the crash-between-calls scenario without adding SQS infrastructure.

**Tests:**
- Unit: mock `incrementClickCount()` to throw `DataAccessException` → verify controller
  still returns 302 (not 500).
- Integration: no changes needed — existing click count tests still verify the happy path.

**Files:**
- `src/main/resources/application.yml`
- `src/main/java/com/urlshortener/controller/UrlController.java`
- `src/test/java/com/urlshortener/unit/UrlControllerTest.java` (new unit test class)

---

## 8.3 Sanitize and Validate External Headers

**Problem:** Two places trust client-supplied headers without validation:

1. `MdcRequestIdFilter` reads `X-Request-ID` and echoes it back in the response via
   `response.setHeader("X-Request-ID", traceId)`. A malicious client can supply
   `X-Request-ID: legit\r\nSet-Cookie: evil=true` — CRLF injection into the response
   headers. Modern Servlet containers (Tomcat 10+) reject CRLF in header values by
   default, but the application should not rely on container behaviour for security.

2. `RateLimitInterceptor.extractClientIp()` trusts the first IP in `X-Forwarded-For`
   without validating that it's a valid IP address. A spoofed `X-Forwarded-For` lets a
   client choose their own rate limit bucket.

**Fix — MdcRequestIdFilter:**

Validate the inbound `X-Request-ID` against a strict allowlist before using it.
If it fails validation, generate a new UUID instead of echoing the client value:

```java
private static final Pattern TRACE_ID_PATTERN =
        Pattern.compile("^[a-zA-Z0-9\\-]{1,36}$");

String inbound = request.getHeader(TRACE_ID_HEADER);
String traceId = (inbound != null && TRACE_ID_PATTERN.matcher(inbound).matches())
        ? inbound
        : UUID.randomUUID().toString();
```

**Fix — RateLimitInterceptor:**

Validate that each segment of `X-Forwarded-For` is a syntactically valid IP address
before accepting it. Use `InetAddress.getByName()` inside a try-catch:

```java
private static String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
        String candidate = xff.split(",")[0].trim();
        if (isValidIpAddress(candidate)) {
            return candidate;
        }
        log.warn("xff_invalid_ip xff={} — falling back to remoteAddr", xff);
    }
    return request.getRemoteAddr();
}

private static boolean isValidIpAddress(String ip) {
    try {
        InetAddress.getByName(ip);
        // Reject empty string (InetAddress accepts it as localhost)
        return !ip.isEmpty();
    } catch (UnknownHostException e) {
        return false;
    }
}
```

**Tests:**
- Unit (MdcRequestIdFilter): request with `X-Request-ID: valid-id` → echoed back unchanged.
  Request with CRLF in header → UUID generated instead.
- Unit (RateLimitInterceptor): `X-Forwarded-For: not-an-ip` → falls back to `remoteAddr`.
  `X-Forwarded-For: 203.0.113.1, 10.0.0.1` → uses `203.0.113.1`.

**Files:**
- `src/main/java/com/urlshortener/config/MdcRequestIdFilter.java`
- `src/main/java/com/urlshortener/config/RateLimitInterceptor.java`
- `src/test/java/com/urlshortener/unit/MdcRequestIdFilterTest.java` (new)
- `src/test/java/com/urlshortener/unit/RateLimitInterceptorTest.java`

---

## 8.4 Split Liveness and Readiness Health Probes

**Problem:** ECS uses a single `/actuator/health` endpoint for both "is the process alive?"
and "should traffic be routed here?". These are different questions:

- **Liveness:** Is the JVM running and responsive? (ECS restart if NO)
- **Readiness:** Can the task serve traffic right now? (ALB deregister if NO)

With a single health endpoint, ECS cannot distinguish between a JVM that is running but
Redis is unreachable (should stop routing traffic, not restart) and a JVM that is fully
hung (should restart).

Spring Boot 2.3+ supports separate liveness and readiness probes natively.

**Fix — application.yml:**

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

This exposes:
- `GET /actuator/health/liveness` — returns UP if the JVM is alive
- `GET /actuator/health/readiness` — returns UP only if Redis and DB connections are healthy

Update the ECS task definition in Terraform to use `/actuator/health/liveness` for the
container health check (restart on failure) and configure the ALB target group health
check to use `/actuator/health/readiness` (deregister on failure).

**Terraform — ECS task definition health check:**

```hcl
health_check = {
  command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health/liveness || exit 1"]
  interval    = 10
  timeout     = 5
  retries     = 3
  start_period = 30
}
```

**Terraform — ALB target group:**

```hcl
resource "aws_lb_target_group" "app" {
  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 10
  }
}
```

**Files:**
- `src/main/resources/application.yml`
- `terraform/modules/ecs/main.tf`
- `terraform/modules/alb/main.tf`

---

## 8.5 Add Request Size Limit and URL Length Validation

**Problem:** `POST /api/v1/urls` accepts a request body of arbitrary size. There is no
`@Size` constraint on `ShortenRequest.url`. A client can POST a 1GB string — it passes
`@NotBlank`, passes `validateUrl()` (URI parsing is lenient on length), and hits the DB.
This is a low-effort DoS vector.

Real URL shorteners typically cap URLs at 2048 characters (the practical IE/URL bar limit)
or 8192 characters (RFC 7230 advisory limit for HTTP requests).

**Fix — ShortenRequest DTO:**

```java
@Builder
public record ShortenRequest(
    @NotBlank
    @Size(max = 2048, message = "url must not exceed 2048 characters")
    String url,

    @Positive
    @Max(value = 3650, message = "ttlDays must not exceed 3650 (10 years)")
    Integer ttlDays
) {}
```

Adding `@Max` on `ttlDays` while here — a caller could pass `ttlDays=999999999` and
produce an `expiresAt` timestamp that overflows `Instant.plus()`.

**Fix — application.yml:**

```yaml
server:
  tomcat:
    max-http-post-size: 8KB   # 2048-char URL + JSON overhead, well under 8KB
```

This rejects oversized requests at the Tomcat layer before Spring even parses the body.

**Tests:**
- Integration: POST with 2049-char URL → 422 INVALID_URL.
- Integration: POST with `ttlDays: 999999999` → 422 INVALID_URL.

**Files:**
- `src/main/java/com/urlshortener/dto/ShortenRequest.java`
- `src/main/resources/application.yml`
- `src/test/java/com/urlshortener/integration/UrlControllerIT.java`

---

## 8.6 Add Hibernate Query Timeout

**Problem:** HikariCP is configured with a 30-second connection timeout, but there is no
query-level timeout. A lock contention on `shortened_urls` (e.g. during a long-running
migration or a runaway `UPDATE`) causes all threads waiting on that query to block
indefinitely. With 20 HikariCP connections per task and 3 tasks, 60 total threads can
be held hostage by one slow query before the connection pool exhausts and new requests
start failing immediately.

**Fix — application.yml:**

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          fetch_size: 50
        query:
          timeout: 5000   # 5 seconds — throws QueryTimeoutException if exceeded
```

5 seconds is generous (typical redirect DB query: <5ms). It catches runaway queries
without affecting normal operation.

This also provides a natural p99 ceiling: no redirect can take more than 5s from the
DB perspective, which aligns with the `url.redirect` p99 < 500ms SLA (the alarm will
fire before the timeout kicks in under normal load).

**Files:**
- `src/main/resources/application.yml`

---

## Verification Checklist

- [ ] `mvn test` — all unit tests pass (0 failures, 0 errors)
- [ ] `mvn verify` — all integration tests pass (requires Docker)
- [ ] Redis mock throws `RedisConnectionFailureException` → redirect returns 302 (not 500)
- [ ] Redis mock throws in rate limiter → request passes through (fail open)
- [ ] `incrementClickCount()` throws → controller still returns 302
- [ ] `X-Request-ID: evil\r\nSet-Cookie: x=y` → response does not contain injected header
- [ ] `X-Forwarded-For: not-an-ip` → rate limit bucket uses `remoteAddr`
- [ ] POST with 2049-char URL → 422
- [ ] POST with `ttlDays: 9999999` → 422
- [ ] `GET /actuator/health/liveness` → 200 UP
- [ ] `GET /actuator/health/readiness` → 200 UP (with Redis + DB connected)
- [ ] JaCoCo 80% gate still passes

## Summary

| Item | Type       | Impact                                          |
|------|------------|-------------------------------------------------|
| 8.1  | Critical   | Redis outage → service outage                   |
| 8.2  | Critical   | Crash between resolve + increment = lost clicks |
| 8.3  | Critical   | Header injection / IP spoofing attack surface   |
| 8.4  | Medium     | ECS can't distinguish liveness from readiness   |
| 8.5  | Medium     | No DoS protection on request body size          |
| 8.6  | Medium     | Slow query can exhaust connection pool silently  |
