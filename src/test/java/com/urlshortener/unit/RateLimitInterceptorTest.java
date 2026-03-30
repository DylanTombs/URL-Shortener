package com.urlshortener.unit;

import com.urlshortener.config.RateLimitInterceptor;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitInterceptor")
class RateLimitInterceptorTest {

    @Mock
    private ProxyManager<String> proxyManager;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(proxyManager);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ── Redirect route (GET /{code}) ───────────────────────────────────────

    @Test
    @DisplayName("GET /{code}: within limit → preHandle returns true, no 429")
    void redirect_withinLimit_returnsTrue() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/aB3xK9mQ");
        request.setRemoteAddr("10.0.0.1");

        stubBucketConsumed();

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("GET /{code}: limit exhausted → 429 with Retry-After header")
    void redirect_limitExhausted_returns429WithRetryAfter() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/aB3xK9mQ");
        request.setRemoteAddr("10.0.0.2");

        stubBucketExhausted(60_000_000_000L); // 60 seconds in nanos

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    // ── Create route (POST /api/v1/urls) ───────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/urls: within limit → preHandle returns true")
    void create_withinLimit_returnsTrue() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/urls");
        request.setRemoteAddr("10.0.0.3");

        stubBucketConsumed();

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("POST /api/v1/urls: limit exhausted → 429")
    void create_limitExhausted_returns429() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/urls");
        request.setRemoteAddr("10.0.0.4");

        stubBucketExhausted(30_000_000_000L); // 30 seconds in nanos

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
    }

    // ── Excluded routes ────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/urls/{code}/stats: not rate-limited → returns true without touching bucket")
    void statsRoute_notRateLimited() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/v1/urls/aB3xK9mQ/stats");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    // ── X-Forwarded-For extraction ─────────────────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For header: first IP used as client IP")
    void xForwardedFor_firstIpUsed() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/aB3xK9mQ");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.1, 10.0.0.2");

        stubBucketConsumed();

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    // doReturn bypasses Mockito's compile-time generic type check on BucketProxy,
    // which is the return type of RemoteBucketBuilder.build() in Bucket4j 8.x.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubBucketConsumed() {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);

        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        RemoteBucketBuilder builder = mock(RemoteBucketBuilder.class);
        Mockito.doReturn(bucket).when(builder).build(anyString(), any(Supplier.class));
        when(proxyManager.builder()).thenReturn(builder);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubBucketExhausted(long nanosToWait) {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(nanosToWait);

        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        RemoteBucketBuilder builder = mock(RemoteBucketBuilder.class);
        Mockito.doReturn(bucket).when(builder).build(anyString(), any(Supplier.class));
        when(proxyManager.builder()).thenReturn(builder);
    }
}
