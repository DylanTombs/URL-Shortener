package com.urlshortener.unit;

import com.urlshortener.config.MdcRequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcRequestIdFilter")
class MdcRequestIdFilterTest {

    private MdcRequestIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new MdcRequestIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    @DisplayName("valid X-Request-ID is echoed back in response header")
    void validTraceId_isEchoedBack() throws Exception {
        request.addHeader("X-Request-ID", "abc-123-DEF");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-ID")).isEqualTo("abc-123-DEF");
    }

    @Test
    @DisplayName("valid UUID X-Request-ID is accepted")
    void validUuid_isAccepted() throws Exception {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        request.addHeader("X-Request-ID", uuid);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-ID")).isEqualTo(uuid);
    }

    @Test
    @DisplayName("missing X-Request-ID generates a UUID")
    void missingTraceId_generatesUuid() throws Exception {
        filter.doFilter(request, response, chain);

        String traceId = response.getHeader("X-Request-ID");
        assertThat(traceId).isNotNull().isNotBlank();
        assertThat(traceId).matches("[a-fA-F0-9\\-]{36}");
    }

    @Test
    @DisplayName("X-Request-ID with CRLF injection is rejected — fresh UUID generated")
    void crlfInjection_isRejected() throws Exception {
        request.addHeader("X-Request-ID", "legit\r\nSet-Cookie: evil=true");

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader("X-Request-ID");
        assertThat(traceId).doesNotContain("Set-Cookie");
        assertThat(traceId).doesNotContain("\r");
        assertThat(traceId).doesNotContain("\n");
        assertThat(traceId).matches("[a-fA-F0-9\\-]{36}");
    }

    @Test
    @DisplayName("X-Request-ID exceeding 36 chars is rejected — fresh UUID generated")
    void tooLongTraceId_isRejected() throws Exception {
        request.addHeader("X-Request-ID", "a".repeat(37));

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-ID")).hasSize(36);
    }

    @Test
    @DisplayName("X-Request-ID with special characters is rejected — fresh UUID generated")
    void specialCharsTraceId_isRejected() throws Exception {
        request.addHeader("X-Request-ID", "bad<script>alert(1)</script>");

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader("X-Request-ID");
        assertThat(traceId).doesNotContain("<");
        assertThat(traceId).matches("[a-fA-F0-9\\-]{36}");
    }
}
