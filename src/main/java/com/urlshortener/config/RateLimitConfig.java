package com.urlshortener.config;

import org.springframework.context.annotation.Configuration;

/**
 * Rate limiting configuration.
 *
 * Primary defence: AWS WAF rule limits 100 requests per 5 minutes per IP
 * at the ALB level — no request reaches the application if the budget is exceeded.
 *
 * Application-level rate limiting (this class) acts as a secondary defence
 * for local / non-AWS deployments and provides more granular per-endpoint control
 * using Bucket4j token-bucket algorithm.
 *
 * TODO (Phase 2): wire Bucket4j Bandwidth + Refill beans and a
 *   HandlerInterceptor that checks and consumes tokens per remote IP.
 */
@Configuration
public class RateLimitConfig {
    // Placeholder — Bucket4j beans and interceptor registration added in Phase 2
}
