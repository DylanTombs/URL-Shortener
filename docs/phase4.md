# Phase 4 — Observability

## Goal
Every request produces a structured JSON log line with a trace ID.
Custom CloudWatch metrics are published from the application.
Alarms fire before users notice problems.
A single dashboard shows the service health at a glance.

---

## 4.1 Structured JSON Logging with Trace ID

### Current State
`application.yml` sets ECS (Elastic Common Schema) log format via Spring Boot's
`logging.structured.format.console: ecs` property. This produces JSON, but lacks a
per-request trace ID to correlate all log lines from a single HTTP request.

### `MdcRequestIdFilter` (new — `config/` package)

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcRequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = Optional.ofNullable(request.getHeader("X-Request-ID"))
                                 .filter(s -> !s.isBlank())
                                 .orElse(UUID.randomUUID().toString());
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-ID", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();   // prevent ThreadLocal leak on pooled threads
        }
    }
}
```

**Why `X-Request-ID` passthrough:** ALB and clients can set their own trace ID.
If present, it is honoured and echoed back in the response header, enabling
end-to-end tracing from the client through CloudWatch Logs.

### Expected Log Format (JSON per line → CloudWatch Logs)

```json
{
  "@timestamp": "2026-03-30T12:00:00.123Z",
  "log.level": "INFO",
  "traceId": "a3f1c7d2-...",
  "log.logger": "com.urlshortener.service.UrlService",
  "message": "redirect_success",
  "code": "aB3xK9mQ",
  "cacheHit": true,
  "latencyMs": 3
}
```

### `logback-spring.xml` (new — `src/main/resources/`)

Explicitly configure the JSON appender so `traceId` from MDC is included in every log line.
Use `net.logstash.logback.encoder.LogstashEncoder` if `logstash-logback-encoder` is on the
classpath, or Spring Boot's built-in ECS encoder. The key requirement: MDC keys (`traceId`)
must appear as top-level JSON fields, not buried in an `mdc` sub-object.

**Files:**
- `src/main/java/com/urlshortener/config/MdcRequestIdFilter.java` (new)
- `src/main/resources/logback-spring.xml` (new)

---

## 4.2 CloudWatch Metrics — `ObservabilityConfig`

`micrometer-registry-cloudwatch2` is already in `pom.xml`. It auto-configures when
`management.cloudwatch.metrics.export.enabled=true`.

### `application.yml` additions

```yaml
management:
  cloudwatch:
    metrics:
      export:
        namespace: UrlShortener
        step: 60s
        enabled: ${CLOUDWATCH_METRICS_ENABLED:true}
  metrics:
    distribution:
      percentiles:
        url.redirect: 0.5, 0.95, 0.99    # publish pre-computed percentiles to CW
      percentiles-histogram:
        url.redirect: false               # skip full histogram to reduce metric count
```

### `ObservabilityConfig` (new — `config/` package)

Tags every metric with the current environment so dev and prod metrics are separable
in the same CloudWatch namespace.

```java
@Configuration
public class ObservabilityConfig {

    @Value("${spring.profiles.active:local}")
    private String environment;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
                                   .commonTags("environment", environment);
    }
}
```

### IAM Permission — ECS Task Role

The ECS task role must have `cloudwatch:PutMetricData`. Add this in Phase 2 ECS
Terraform work (already noted there):
```hcl
statement {
  actions   = ["cloudwatch:PutMetricData"]
  resources = ["*"]
  condition {
    test     = "StringEquals"
    variable = "cloudwatch:namespace"
    values   = ["UrlShortener"]
  }
}
```

**Files:**
- `src/main/java/com/urlshortener/config/ObservabilityConfig.java` (new)
- `src/main/resources/application.yml`

---

## 4.3 Custom Metrics in `UrlService`

Inject `MeterRegistry` via constructor. Instrument four events:

### Metrics Table

| Metric | Type | Tags | Emitted |
|--------|------|------|---------|
| `url.created` | Counter | — | After successful `save()` in `shorten()` |
| `url.redirect` | Timer | `cache_hit=true\|false` | Wraps entire `resolveUrl()` |
| `url.not_found` | Counter | — | When `UrlNotFoundException` is thrown in `resolveUrl()` |
| `url.expired` | Counter | — | When `UrlExpiredException` is thrown in `resolveUrl()` |

### Cache Hit Rate Derivation

No separate metric needed. In Terraform CloudWatch math expressions:
```
hitRate = url.redirect[cache_hit=true].count / url.redirect.count
```

### Redirect Timer Implementation

```java
public String resolveUrl(String code) {
    Timer.Sample sample = Timer.start(meterRegistry);
    boolean cacheHit = false;
    try {
        // ... fetch from cache or DB ...
        // set cacheHit = true if served from Redis
        return longUrl;
    } catch (UrlNotFoundException e) {
        meterRegistry.counter("url.not_found").increment();
        throw e;
    } catch (UrlExpiredException e) {
        meterRegistry.counter("url.expired").increment();
        throw e;
    } finally {
        sample.stop(Timer.builder("url.redirect")
            .tag("cache_hit", String.valueOf(cacheHit))
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry));
    }
}
```

**Determining cache hit:** Spring's `@Cacheable` doesn't expose a hit/miss signal.
Options:
1. Remove `@Cacheable` and manage the Redis cache manually (full control, more code)
2. Add a `CacheEventListener` / `CacheInterceptor` extension (complex)
3. Check the cache directly before delegating to `@Cacheable` (simplest)

**Recommended — manual cache check** in `resolveUrl()`:
```java
Cache cache = cacheManager.getCache("urls");
Cache.ValueWrapper cached = cache.get(code);
boolean cacheHit = cached != null;
```
Then call the repository only on cache miss, and populate the cache manually.
This replaces `@Cacheable` on `resolveUrl()` with explicit cache logic — worth it for
the observability gain.

**Files:** `src/main/java/com/urlshortener/service/UrlService.java`

---

## 4.4 Terraform — CloudWatch Module

New module at `terraform/modules/cloudwatch/`.

### Module Variables (`variables.tf`)

| Variable | Description |
|----------|-------------|
| `app_name` | e.g. `url-shortener` |
| `environment` | `dev` or `prod` |
| `db_instance_id` | RDS instance identifier |
| `alb_arn_suffix` | ALB ARN suffix for `AWS/ApplicationELB` namespace |
| `alarm_email` | SNS subscription email (empty = no subscription) |

### SNS Topic and Subscription

```hcl
resource "aws_sns_topic" "alarms" {
  name = "${var.app_name}-${var.environment}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}
```

### 4 CloudWatch Alarms

**Alarm 1 — p99 Redirect Latency > 500ms**
```hcl
resource "aws_cloudwatch_metric_alarm" "redirect_p99_latency" {
  alarm_name          = "${var.app_name}-${var.environment}-redirect-p99-latency"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "url.redirect.percentile['0.99']"
  namespace           = "UrlShortener"
  period              = 60
  statistic           = "Maximum"
  threshold           = 500
  alarm_description   = "p99 redirect latency exceeded 500ms"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
  dimensions          = { environment = var.environment }
}
```

**Alarm 2 — Cache Hit Rate < 70%**
Uses CloudWatch math expression (metric_query blocks):
```hcl
resource "aws_cloudwatch_metric_alarm" "cache_hit_rate_low" {
  alarm_name          = "${var.app_name}-${var.environment}-cache-hit-rate-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 3
  threshold           = 0.7
  alarm_description   = "Cache hit rate below 70% — Redis may be cold or unhealthy"
  alarm_actions       = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "hitRate"
    expression  = "IF(total > 0, hits/total, 1)"
    label       = "Cache Hit Rate"
    return_data = true
  }
  metric_query {
    id = "hits"
    metric {
      namespace   = "UrlShortener"
      metric_name = "url.redirect.count"
      dimensions  = { cache_hit = "true", environment = var.environment }
      period      = 300
      stat        = "Sum"
    }
  }
  metric_query {
    id = "total"
    metric {
      namespace   = "UrlShortener"
      metric_name = "url.redirect.count"
      dimensions  = { environment = var.environment }
      period      = 300
      stat        = "Sum"
    }
  }
}
```
Note: `IF(total > 0, ...)` prevents false alarms during zero-traffic periods.

**Alarm 3 — RDS CPU > 80%**
```hcl
resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.app_name}-${var.environment}-rds-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  alarm_actions       = [aws_sns_topic.alarms.arn]
  dimensions          = { DBInstanceIdentifier = var.db_instance_id }
}
```

**Alarm 4 — ALB 5xx Error Rate > 1%**
```hcl
resource "aws_cloudwatch_metric_alarm" "alb_5xx_rate" {
  alarm_name          = "${var.app_name}-${var.environment}-alb-5xx-rate"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 1.0
  alarm_actions       = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "errorRate"
    expression  = "IF(requests > 0, errors/requests*100, 0)"
    return_data = true
  }
  metric_query {
    id = "errors"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      period      = 60
      stat        = "Sum"
    }
  }
  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      period      = 60
      stat        = "Sum"
    }
  }
}
```

### CloudWatch Dashboard (6 Widgets)

```hcl
resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.app_name}-${var.environment}"
  dashboard_body = jsonencode({
    widgets = [
      # Row 1, Col 1: Redirect Latency percentiles
      { type="metric", x=0,  y=0, width=8, height=6, properties={
          title   = "Redirect Latency (p50/p95/p99)"
          view    = "timeSeries"
          period  = 60
          metrics = [
            ["UrlShortener", "url.redirect.percentile['0.5']",  "environment", var.environment, { label="p50" }],
            ["UrlShortener", "url.redirect.percentile['0.95']", "environment", var.environment, { label="p95" }],
            ["UrlShortener", "url.redirect.percentile['0.99']", "environment", var.environment, { label="p99" }]
          ]
      }},
      # Row 1, Col 2: Cache Hit Rate (math expression)
      { type="metric", x=8,  y=0, width=8, height=6, properties={
          title  = "Cache Hit Rate"
          view   = "timeSeries"
          period = 300
          metrics = [
            [{ expression="hits/total", label="Hit Rate", id="hitRate" }],
            ["UrlShortener", "url.redirect.count", "cache_hit", "true",  "environment", var.environment, { id="hits",  visible=false }],
            ["UrlShortener", "url.redirect.count", "environment", var.environment, { id="total", visible=false }]
          ]
      }},
      # Row 1, Col 3: Redirects per minute
      { type="metric", x=16, y=0, width=8, height=6, properties={
          title   = "Redirects / min"
          view    = "timeSeries"
          period  = 60
          stat    = "Sum"
          metrics = [["UrlShortener", "url.redirect.count", "environment", var.environment]]
      }},
      # Row 2, Col 1: RDS CPU
      { type="metric", x=0,  y=6, width=8, height=6, properties={
          title   = "RDS CPU Utilization"
          view    = "timeSeries"
          period  = 60
          metrics = [["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", var.db_instance_id]]
      }},
      # Row 2, Col 2: ALB 5xx Rate
      { type="metric", x=8,  y=6, width=8, height=6, properties={
          title   = "ALB 5xx Error Rate (%)"
          view    = "timeSeries"
          period  = 60
          metrics = [
            [{ expression="errors/requests*100", label="5xx Rate %", id="rate" }],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, { id="errors",   visible=false }],
            ["AWS/ApplicationELB", "RequestCount",              "LoadBalancer", var.alb_arn_suffix, { id="requests", visible=false }]
          ]
      }},
      # Row 2, Col 3: URLs created per minute
      { type="metric", x=16, y=6, width=8, height=6, properties={
          title   = "URLs Created / min"
          view    = "timeSeries"
          period  = 60
          stat    = "Sum"
          metrics = [["UrlShortener", "url.created.count", "environment", var.environment]]
      }}
    ]
  })
}
```

**Files to create:**
- `terraform/modules/cloudwatch/main.tf`
- `terraform/modules/cloudwatch/variables.tf`
- `terraform/modules/cloudwatch/outputs.tf`

**Files to modify:**
- `terraform/environments/dev/main.tf` — wire `module "cloudwatch"`
- `terraform/environments/prod/main.tf` — wire `module "cloudwatch"`

---

## Architectural Challenges

### 1. Metric Cardinality
Each unique combination of metric name + dimension values is a separate CloudWatch metric
(billed per metric per month). Tags are limited to `environment` (string) and `cache_hit`
(boolean) on the redirect timer — never per-code or per-URL tags. This keeps metric count
constant regardless of traffic volume.

### 2. Pre-Computed Percentiles
CloudWatch does not natively compute percentiles from raw count/sum data.
`micrometer-registry-cloudwatch2` publishes p50/p95/p99 as separate metric data points
(e.g. `url.redirect.percentile['0.99']`). The alarm and dashboard reference these by name.
This is correct — do not try to use CloudWatch's built-in statistics for percentile alarms
on application-side metrics.

### 3. Cache Hit Rate Alarm Noise
A single cache miss in a 5-minute window with low traffic would produce a hit rate of 0%,
triggering a false alarm. The `IF(total > 0, hits/total, 1)` expression returns 1 (100%)
when there are no requests, preventing noise during off-hours.

### 4. CloudWatch Metrics Publish Delay
Micrometer CloudWatch2 publishes on a `step` interval (60 seconds). Metrics are not
visible in CloudWatch until the first publish cycle completes. Allow 90 seconds after
first request before expecting metrics to appear.

---

## Verification

```bash
# After 10 redirects, list published metrics
aws cloudwatch list-metrics --namespace UrlShortener

# Deliberately trigger a 404
curl -v https://<alb-dns>/nonexistent_code_xyz

# Verify url.not_found incremented (wait 90s for publish)
aws cloudwatch get-metric-statistics \
  --namespace UrlShortener \
  --metric-name url.not_found.count \
  --dimensions Name=environment,Value=dev \
  --start-time $(date -u -v-5M +%Y-%m-%dT%H:%M:%SZ) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%SZ) \
  --period 300 \
  --statistics Sum
```

- Dashboard renders all 6 widgets without `INSUFFICIENT_DATA`
- All 4 alarms show `OK` state under normal load within 5 minutes
- Log lines in CloudWatch Logs Insights contain `traceId` field
- Every request's log lines share the same `traceId` value
