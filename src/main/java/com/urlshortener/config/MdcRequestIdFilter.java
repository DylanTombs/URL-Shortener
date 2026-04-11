package com.urlshortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter that assigns a trace ID to every HTTP request and places it
 * in the SLF4J MDC so it appears in every log line produced during that request.
 *
 * Trace ID source (in priority order):
 *   1. X-Request-ID header set by the upstream caller (ALB or client)
 *   2. Generated UUID if no header is present
 *
 * The trace ID is echoed back in the X-Request-ID response header so clients
 * and ALB access logs can correlate a request end-to-end.
 *
 * HIGHEST_PRECEDENCE ensures the MDC is populated before any other filter or
 * interceptor (including RateLimitInterceptor) produces log output.
 *
 * MDC.clear() in the finally block prevents ThreadLocal leaks on pooled threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcRequestIdFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Request-ID";
    static final String MDC_KEY = "traceId";

    // Allowlist: alphanumeric and hyphens only, max 36 chars (UUID length).
    // Rejects CRLF injection attempts and other header-smuggling payloads.
    // If the inbound value fails validation we generate a fresh UUID instead of
    // echoing untrusted data back in the response header.
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-]{1,36}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String inbound = request.getHeader(TRACE_ID_HEADER);
        String traceId = (inbound != null && TRACE_ID_PATTERN.matcher(inbound).matches())
                ? inbound
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
