# Phase 6 — Code Quality & Correctness

## Goal
Close the gaps identified in the FAANG-level review. Four issues are correctness bugs
(the code either silently misbehaves or contradicts its own documentation). Four are
code quality issues that would be flagged in any Amazon code review loop. All must be
fixed before this project can be called senior-level.

Issues are ordered: correctness bugs first, then quality issues, then minor polish.

---

## 6.1 Fix Cache TTL — Implement `min(24h, time-to-expiry)`

**Problem:** `ARCHITECTURE.md` and `DECISIONS.md` both describe the cache TTL invariant:
> TTL = min(24 hours, time remaining until link expiry)

`UrlService.resolveUrl()` calls `cache.put(code, url.getLongUrl())` which always uses
`RedisConfig`'s 24-hour default TTL. A URL expiring in 10 minutes is cached for 24 hours.
The code contradicts the documentation and will serve a 301 redirect to an expired URL.

**Fix:** After the DB lookup, compute the per-entry TTL and write via
`RedisCacheWriter` directly (since Spring's `Cache` abstraction doesn't expose per-entry
TTL). Or, replace `CacheManager` usage in the resolveUrl hot path with a
`RedisTemplate<String, String>` that allows `setex`-style writes.

Concrete approach — inject `StringRedisTemplate` alongside `CacheManager` and use it
only for the write:

```java
// In resolveUrl(), replace cache.put(code, url.getLongUrl()) with:
Duration ttl = computeCacheTtl(url.getExpiresAt());
stringRedisTemplate.opsForValue().set("urls::" + code, url.getLongUrl(), ttl);
```

```java
private static final Duration MAX_CACHE_TTL = Duration.ofHours(24);

private static Duration computeCacheTtl(Instant expiresAt) {
    if (expiresAt == null) {
        return MAX_CACHE_TTL;
    }
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    return remaining.isNegative() ? Duration.ZERO
            : remaining.compareTo(MAX_CACHE_TTL) < 0 ? remaining : MAX_CACHE_TTL;
}
```

The cache prefix `urls::` matches Spring Cache's default key convention so
`CacheManager.getCache("urls").get(code)` reads still work.

**Test:** Add a unit test in `UrlServiceTest` that creates a URL expiring in 5 minutes,
calls `resolveUrl()`, and asserts that the TTL written to `StringRedisTemplate` is
approximately 5 minutes (not 24 hours). Use `ArgumentCaptor` on
`stringRedisTemplate.opsForValue().set(...)`.

Add an integration test in `UrlControllerIT` that:
1. Inserts a URL expiring in the past (simulating a race where expiry occurs after the DB
   read but before the cache write would matter)
2. Confirms a 410 is returned, not a 301

**Files:**
- `src/main/java/com/urlshortener/service/UrlService.java`
- `src/test/java/com/urlshortener/unit/UrlServiceTest.java`
- `src/test/java/com/urlshortener/integration/UrlControllerIT.java`

---

## 6.2 Fix Race Condition in `shorten()` — Catch `DataIntegrityViolationException`

**Problem:** `generateUniqueCode()` checks `existsByCode(code)` then calls `save()`.
These two operations are not atomic. Under concurrent load, two threads can both pass the
`existsByCode` check for the same code and both attempt `save()`. The second save throws
`DataIntegrityViolationException` (PostgreSQL UNIQUE constraint violation), which is not
caught and propagates as a 500 Internal Server Error.

This is not theoretical — it is guaranteed to happen in load tests.

**Fix:** Wrap `urlRepository.save()` in a try/catch for
`DataIntegrityViolationException` and retry with a new code, exactly as the collision
loop already does:

```java
@Transactional
public ShortenResponse shorten(ShortenRequest request, String baseUrl) {
    validateUrl(request.url());

    Instant expiresAt = request.ttlDays() != null
            ? Instant.now().plus(request.ttlDays(), ChronoUnit.DAYS)
            : null;

    for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
        String code = codeGenerator.generate();
        if (urlRepository.existsByCode(code)) {
            log.warn("code_collision attempt={} code={}", attempt, code);
            continue;
        }
        try {
            ShortenedUrl entity = ShortenedUrl.builder()
                    .code(code)
                    .longUrl(request.url())
                    .createdAt(Instant.now())
                    .expiresAt(expiresAt)
                    .clickCount(0)
                    .build();
            ShortenedUrl saved = urlRepository.save(entity);
            meterRegistry.counter("url.created").increment();
            log.info("url_shortened code={} ttlDays={}", saved.getCode(), request.ttlDays());
            return ShortenResponse.builder()
                    .code(saved.getCode())
                    .shortUrl(baseUrl + "/" + saved.getCode())
                    .expiresAt(saved.getExpiresAt())
                    .build();
        } catch (DataIntegrityViolationException e) {
            log.warn("code_collision_concurrent attempt={} code={}", attempt, code);
        }
    }
    throw new IllegalStateException(
            "Failed to generate unique code after " + MAX_COLLISION_RETRIES + " attempts");
}
```

Also remove `Objects.requireNonNull(entity)` — `entity` is constructed immediately above
and cannot be null. This is dead noise.

**Test:** Add a unit test in `UrlServiceTest` that stubs `urlRepository.save()` to throw
`DataIntegrityViolationException` on the first call and succeed on the second, and asserts
that `shorten()` returns successfully.

**Files:**
- `src/main/java/com/urlshortener/service/UrlService.java`
- `src/test/java/com/urlshortener/unit/UrlServiceTest.java`

---

## 6.3 Replace `IllegalArgumentException` Handler with Custom `InvalidUrlException`

**Problem:** `GlobalExceptionHandler` catches all `IllegalArgumentException` and maps
them to 422 UNPROCESSABLE_ENTITY. `IllegalArgumentException` is a general-purpose JDK
exception thrown by Spring, Hibernate, Jackson, and many third-party libraries. Any
future code path that throws `IllegalArgumentException` for any reason will silently
become a 422 "INVALID_URL" response — including internal framework errors.

**Fix:**

1. Create `src/main/java/com/urlshortener/exception/InvalidUrlException.java`:

```java
package com.urlshortener.exception;

public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
    public InvalidUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

2. Update `UrlService.validateUrl()` to throw `InvalidUrlException` instead of
   `IllegalArgumentException`:

```java
private void validateUrl(String url) {
    try {
        URI uri = new URI(url);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new InvalidUrlException("Invalid URL: missing scheme or host: " + url);
        }
        if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
            throw new InvalidUrlException("Invalid URL: scheme must be http or https: " + url);
        }
    } catch (URISyntaxException e) {
        throw new InvalidUrlException("Invalid URL: " + url, e);
    }
}
```

3. Update `GlobalExceptionHandler` to handle `InvalidUrlException` instead of
   `IllegalArgumentException`:

```java
@ExceptionHandler(InvalidUrlException.class)
public ResponseEntity<ErrorResponse> handleInvalidUrl(InvalidUrlException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new ErrorResponse("INVALID_URL", ex.getMessage()));
}
```

**Test:** Existing tests cover the 422 path. Update `UrlServiceTest` to assert
`InvalidUrlException` is thrown (not `IllegalArgumentException`). No integration test
changes needed — the HTTP behaviour is identical.

**Files:**
- `src/main/java/com/urlshortener/exception/InvalidUrlException.java` (new)
- `src/main/java/com/urlshortener/service/UrlService.java`
- `src/main/java/com/urlshortener/controller/GlobalExceptionHandler.java`
- `src/test/java/com/urlshortener/unit/UrlServiceTest.java`

---

## 6.4 Fix JSON Logging — Replace Pattern Encoder with `logstash-logback-encoder`

**Problem:** `logback-spring.xml` constructs JSON via a pattern string:

```xml
<pattern>{"@timestamp":"%d{...}","log.level":"%level","traceId":"%X{traceId:-none}",...}%n</pattern>
```

If a log message contains a double-quote, backslash, newline, or control character
(e.g. an exception message or a URL with query parameters), the output is invalid JSON.
CloudWatch Logs Insights will fail to parse the line — losing the structured field
extraction that makes the traceId filtering work.

**Fix:** Add `logstash-logback-encoder` and use `LogstashEncoder`, which correctly
escapes all values:

1. Add dependency to `pom.xml`:

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

2. Rewrite `logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <springProfile name="!test">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <!-- MDC keys promoted to top-level JSON fields -->
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <!-- Remove the default "tags" array field (unused) -->
        <excludeProvider class="net.logstash.logback.composite.loggingevent.TagsJsonProvider"/>
        <fieldNames>
          <timestamp>@timestamp</timestamp>
          <level>log.level</level>
          <logger>log.logger</logger>
          <thread>thread</thread>
          <message>message</message>
          <stackTrace>error.stack_trace</stackTrace>
        </fieldNames>
      </encoder>
    </appender>

    <root level="INFO">
      <appender-ref ref="CONSOLE"/>
    </root>

    <logger name="com.urlshortener" level="DEBUG"/>
  </springProfile>

  <springProfile name="test">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
      </encoder>
    </appender>

    <root level="WARN">
      <appender-ref ref="CONSOLE"/>
    </root>

    <logger name="com.urlshortener" level="INFO"/>
  </springProfile>

</configuration>
```

**Verify:** After the change, start the app locally and confirm:
```bash
mvn spring-boot:run 2>&1 | head -5 | python3 -m json.tool
```
Every line must parse cleanly. Test with a URL containing `"` or `\` in the long_url
path to confirm escaping works.

**Files:**
- `pom.xml`
- `src/main/resources/logback-spring.xml`

---

## 6.5 Fix Redis Serializer — Use `StringRedisSerializer` for URL Values

**Problem:** `RedisConfig` uses `GenericJackson2JsonRedisSerializer` for cache values.
This serializer embeds Java type metadata in the stored value:

```
# What's actually in Redis:
GET urls::aB3xK9mQ
["java.lang.String","https://example.com/original"]
```

Problems:
- Wastes storage (type wrapper around every plain string)
- Makes `redis-cli GET` unreadable in production debugging
- Creates deserialization coupling — renaming the value type breaks cache reads
- Incompatible with the `StringRedisTemplate` writes introduced in 6.1

**Fix:** Update `RedisConfig` to use `StringRedisSerializer` for values in the `urls`
cache:

```java
@Bean
public RedisCacheManager cacheManager(
        RedisConnectionFactory connectionFactory) {

    RedisCacheConfiguration urlsConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(DEFAULT_TTL)
            .serializeKeysWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                            new StringRedisSerializer()))
            .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                            new StringRedisSerializer()))
            .disableCachingNullValues();

    return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(urlsConfig)
            .withInitialCacheConfigurations(Map.of("urls", urlsConfig))
            .build();
}
```

Remove the now-unused `defaultCacheConfig()` bean and `GenericJackson2JsonRedisSerializer`
import.

**Note:** This is a breaking change for any live Redis cache — existing entries stored
with the Jackson serializer will fail to deserialize. In production, flush the `urls`
cache after deploying (see RUNBOOK.md §Redis Cache Flush).

**Files:**
- `src/main/java/com/urlshortener/config/RedisConfig.java`

---

## 6.6 Remove Redundant Index on `code`

**Problem:** The schema creates a redundant index:

```sql
code VARCHAR(12) UNIQUE NOT NULL,   -- PostgreSQL creates a B-tree index for this
...
CREATE INDEX idx_shortened_urls_code ON shortened_urls (code);  -- duplicate
```

PostgreSQL automatically creates a B-tree index to enforce every UNIQUE constraint.
`idx_shortened_urls_code` is identical to the constraint index and wastes storage,
slows down writes (two index updates per insert), and confuses anyone reading the schema.

**Fix:** Create `db/migration/V2__remove_redundant_code_index.sql`:

```sql
-- The UNIQUE constraint on code already creates a B-tree index.
-- This explicit index is a duplicate — drop it.
DROP INDEX IF EXISTS idx_shortened_urls_code;
```

**Files:**
- `db/migration/V2__remove_redundant_code_index.sql` (new)

---

## 6.7 Add Redirect Request Logging

**Problem:** A successful cache-hit redirect leaves no application log trace.
The only evidence is the Micrometer timer. This makes customer-reported issues
("I was redirected to the wrong URL") difficult to investigate — there is no log
line to grep for the specific code at a specific timestamp.

**Fix:** Add a single `log.info` in `resolveUrl()` for the cache-hit path. The
cache-miss path already logs. Ensure both paths log the same fields so Logs Insights
queries work uniformly.

In `UrlService.resolveUrl()`:

```java
if (cached != null) {
    cacheHit = true;
    log.info("url_resolved code={} cacheHit=true", code);  // add this line
    return (String) cached.get();
}
```

And update the cache-miss log line to use structured fields consistently:

```java
// Replace the existing log line after the DB fetch:
log.debug("url_resolved code={} cacheHit=false", code);  // debug — already on miss path
```

**Files:**
- `src/main/java/com/urlshortener/service/UrlService.java`

---

## 6.8 Minor: Fix `actuator/health` `show-details` for Production

**Problem:** `application.yml` has:

```yaml
management:
  endpoint:
    health:
      show-details: always
```

`show-details: always` exposes database connection pool state, Redis connection
details, and Hikari pool metrics to any unauthenticated caller of `/actuator/health`.
This would fail an AWS security review and is noted in Amazon's Well-Architected
Framework under "Protect Data in Transit and at Rest".

**Fix:** Split the setting by profile. `always` is fine locally and in tests (useful
for debugging). Production should use `when_authorized`.

In `application.yml`:
```yaml
management:
  endpoint:
    health:
      show-details: when_authorized
```

Override in `application-dev.yml` (create if it doesn't exist):
```yaml
management:
  endpoint:
    health:
      show-details: always
```

Local development and tests still get full detail. Production (no active profile
matching `dev`) gets `when_authorized`.

**Files:**
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml` (new)

---

## 6.9 Minor: Remove Unused Prometheus Endpoint

**Problem:** The Prometheus scrape endpoint is exposed:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

There is no Prometheus scraper, no Grafana, and CloudWatch is the metrics backend.
An exposed `/actuator/prometheus` endpoint with no consumer is an attack surface
(exposes internal metrics to anyone with network access) and causes confusion
(future engineers will wonder why Prometheus is configured).

**Fix:** Remove `prometheus` from the exposure list:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

If Prometheus/Grafana is added in a future phase, add it back with authentication.

**Files:**
- `src/main/resources/application.yml`

---

## 6.10 Minor: Add `extractCode` JSON Parsing to `UrlControllerIT`

**Problem:** `UrlControllerIT.extractCode()` uses string index arithmetic:

```java
int start = json.indexOf("\"code\":\"") + 8;
int end = json.indexOf('"', start);
return json.substring(start, end);
```

This breaks silently if JSON field ordering changes and produces a confusing
`StringIndexOutOfBoundsException` with index -1 rather than a clear assertion failure.
Jackson `ObjectMapper` is already on the classpath via `spring-boot-starter-test`.

**Fix:**

```java
@Autowired
private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

private String extractCode(String json) throws Exception {
    return objectMapper.readTree(json).get("code").asText();
}
```

**Files:**
- `src/test/java/com/urlshortener/integration/UrlControllerIT.java`

---

## 6.11 Minor: Add `@EnableCaching` to `RedisConfig`

**Problem:** `RedisConfig` configures `RedisCacheManager` but does not declare
`@EnableCaching`. It currently works because Spring Boot's auto-configuration enables
caching, but a class that defines the cache manager should own the annotation — a
reviewer reading `RedisConfig` in isolation has no way to know caching is enabled.

**Fix:** Add `@EnableCaching` to `RedisConfig`:

```java
@Configuration
@EnableCaching
public class RedisConfig {
```

**Files:**
- `src/main/java/com/urlshortener/config/RedisConfig.java`

---

## Verification Checklist

- [ ] `mvn verify` — all tests pass, JaCoCo ≥ 80%
- [ ] Start locally with `mvn spring-boot:run` and pipe output through `python3 -m json.tool` — every line is valid JSON
- [ ] `redis-cli GET "urls::aB3xK9mQ"` returns a plain string, not a Jackson-wrapped array
- [ ] `curl http://localhost:8080/actuator/health` with no profile active returns `{"status":"UP"}` without pool/connection details
- [ ] Create a URL with `ttlDays: 1`, then inspect the Redis TTL with `redis-cli TTL "urls::<code>"` — must be ≤ 86400 seconds
- [ ] Insert a URL with `expires_at` 5 minutes from now, check Redis TTL is ~300 seconds
- [ ] `psql` into the DB: `\d shortened_urls` — confirm only one index on `code` (the constraint index), not two
- [ ] Run the rate limit integration test — confirms 429 on the 61st request still works after all changes
