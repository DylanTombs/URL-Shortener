package com.urlshortener.unit;

import com.urlshortener.controller.UrlController;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.CodeGenerator;
import com.urlshortener.service.UrlService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for UrlController using direct instantiation + subclass stubs.
 *
 * @WebMvcTest and @MockitoBean are intentionally avoided: both require
 * inline-mocking of concrete classes which ByteBuddy cannot instrument on JDK 21+.
 * UrlService is stubbed via anonymous subclass — no instrumentation needed.
 * End-to-end behaviour is covered by UrlControllerIT (Testcontainers, full stack).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UrlController unit")
class UrlControllerTest {

    // UrlService constructor args — all interfaces, trivially mockable
    @Mock private UrlRepository urlRepository;
    @Mock private CodeGenerator codeGenerator;
    @Mock private CacheManager cacheManager;
    @Mock private ValueOperations<String, String> redisValueOps;

    // ── Click count increment isolation ────────────────────────────────────
    //
    // Guards the isolated try/catch in UrlController.redirect(). If that catch
    // is removed, a DB write failure on incrementClickCount causes a 500 instead
    // of completing the 302 redirect. This test fails if the catch is removed.

    @Test
    @DisplayName("redirect: incrementClickCount throws → 302 still returned")
    void redirect_incrementClickCountThrows_still302() {
        UrlService stub = new UrlService(
                urlRepository, codeGenerator, cacheManager,
                new SimpleMeterRegistry(), redisValueOps) {
            @Override public String resolveUrl(String code) {
                return "https://example.com/target";
            }
            @Override public void incrementClickCount(String code) {
                throw new DataAccessResourceFailureException("DB connection lost");
            }
        };

        UrlController controller = new UrlController(stub);
        ReflectionTestUtils.setField(controller, "baseUrl", "https://sho.rt");

        ResponseEntity<Void> response = controller.redirect("aB3xK9mQ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/target");
    }

    @Test
    @DisplayName("redirect: resolveUrl succeeds → 302 with correct Location header")
    void redirect_validCode_returns302WithLocation() {
        UrlService stub = new UrlService(
                urlRepository, codeGenerator, cacheManager,
                new SimpleMeterRegistry(), redisValueOps) {
            @Override public String resolveUrl(String code) {
                return "https://example.com/original";
            }
        };

        UrlController controller = new UrlController(stub);
        ReflectionTestUtils.setField(controller, "baseUrl", "https://sho.rt");

        ResponseEntity<Void> response = controller.redirect("aB3xK9mQ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/original");
    }
}
