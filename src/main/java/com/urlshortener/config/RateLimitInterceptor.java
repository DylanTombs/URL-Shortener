package com.urlshortener.config;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * HTTP interceptor that enforces per-IP rate limits using Bucket4j.
 *
 * Route limits (applied after WAF's 100 req/5 min primary defence):
 *   GET /{code}        — 60 req/min  (redirect traffic)
 *   POST /api/v1/urls  — 10 req/min  (create traffic)
 *
 * Client IP: extracted from X-Forwarded-For (set by ALB); falls back to
 * remoteAddr for local/direct calls.
 *
 * On limit exceeded: 429 with Retry-After header and structured JSON body.
 */
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ProxyManager<String> proxyManager;

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {

        String routeKey = resolveRouteKey(request);
        if (routeKey == null) {
            return true; // no rate limit for this route
        }

        String clientIp = extractClientIp(request);
        String bucketKey = clientIp + ":" + routeKey;
        BucketConfiguration config = "redirect".equals(routeKey)
                ? RateLimitConfig.redirectBucketConfig()
                : RateLimitConfig.createBucketConfig();

        ConsumptionProbe probe = proxyManager.builder()
                .build(bucketKey, () -> config)
                .tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return true;
        }

        long waitSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.warn("rate_limit_exceeded clientIp={} route={} retryAfterSeconds={}", clientIp, routeKey, waitSeconds);

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(waitSeconds));
        response.getWriter().write(
                "{\"error\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests. Try again in "
                + waitSeconds + " seconds.\"}");
        return false;
    }

    // ---- private helpers -------------------------------------------------

    /**
     * Returns "redirect" for GET /{code}, "create" for POST /api/v1/urls, null otherwise.
     */
    private static String resolveRouteKey(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method) && "/api/v1/urls".equals(uri)) {
            return "create";
        }
        if ("GET".equalsIgnoreCase(method)
                && uri.length() > 1
                && !uri.startsWith("/api/")
                && !uri.startsWith("/actuator/")) {
            return "redirect";
        }
        return null;
    }

    /**
     * Extracts the real client IP from X-Forwarded-For (set by ALB).
     * ALB always appends the actual client IP as the last value, but the
     * first value is the original source — which is what we want for rate limiting.
     */
    private static String extractClientIp(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader("X-Forwarded-For"))
                .map(xff -> xff.split(",")[0].trim())
                .orElse(request.getRemoteAddr());
    }
}
