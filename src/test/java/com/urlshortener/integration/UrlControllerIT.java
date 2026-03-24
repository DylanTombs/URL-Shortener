package com.urlshortener.integration;

import com.redis.testcontainers.RedisContainer;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests using real Postgres and Redis via Testcontainers.
 * No H2, no mocks — this exercises the full application stack.
 *
 * Container lifecycle: static @Container fields = started once per test class,
 * shared across all test methods (fast; ~10s startup paid once).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("UrlController Integration")
class UrlControllerIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("urlshortener_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    static final RedisContainer redis =
            new RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired UrlRepository urlRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void cleanState() {
        urlRepository.deleteAll();
        // Clear Redis cache so previous test results don't bleed across tests
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    // ---- POST /api/v1/urls -----------------------------------------------

    @Test
    @DisplayName("POST /api/v1/urls: valid URL returns 201 with code and shortUrl")
    void shorten_validUrl_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "https://www.example.com/some/long/path"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("[a-zA-Z0-9]{8}")))
                .andExpect(jsonPath("$.shortUrl").value(containsString("/sho.rt/")))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/urls: valid URL with ttlDays returns expiresAt")
    void shorten_withTtl_returnsExpiresAt() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "https://www.example.com", "ttlDays": 7}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/urls: blank URL returns 422")
    void shorten_blankUrl_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": ""}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_URL"));
    }

    @Test
    @DisplayName("POST /api/v1/urls: invalid URL scheme returns 422")
    void shorten_invalidScheme_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "ftp://example.com"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_URL"));
    }

    @Test
    @DisplayName("POST /api/v1/urls: missing url field returns 422")
    void shorten_missingUrl_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- GET /{code} -----------------------------------------------------

    @Test
    @DisplayName("GET /{code}: known code returns 301 with Location header")
    void redirect_knownCode_returns301() throws Exception {
        // Shorten first
        String response = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "https://www.example.com/redirect-target"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String code = extractCode(response);

        mockMvc.perform(get("/" + code))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://www.example.com/redirect-target"));
    }

    @Test
    @DisplayName("GET /{code}: cache hit serves redirect without DB on second call")
    void redirect_knownCode_servedFromCache() throws Exception {
        String response = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "https://www.example.com/cached"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String code = extractCode(response);

        // First call — cache miss, hits DB
        mockMvc.perform(get("/" + code))
                .andExpect(status().isMovedPermanently());

        // Second call — should be served from Redis cache
        mockMvc.perform(get("/" + code))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://www.example.com/cached"));
    }

    @Test
    @DisplayName("GET /{code}: unknown code returns 404")
    void redirect_unknownCode_returns404() throws Exception {
        mockMvc.perform(get("/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /{code}: expired code returns 410")
    void redirect_expiredCode_returns410() throws Exception {
        // Insert expired record directly — bypasses service TTL logic intentionally
        urlRepository.save(ShortenedUrl.builder()
                .code("expired1")
                .longUrl("https://example.com")
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().minusSeconds(60))
                .clickCount(0)
                .build());

        mockMvc.perform(get("/expired1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("EXPIRED"));
    }

    // ---- GET /api/v1/urls/{code}/stats -----------------------------------

    @Test
    @DisplayName("GET /api/v1/urls/{code}/stats: returns 200 with code and clickCount")
    void stats_knownCode_returns200() throws Exception {
        // Shorten to get a real code in the DB
        String response = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "https://www.example.com/stats-target"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String code = extractCode(response);

        mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.createdAt", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/v1/urls/{code}/stats: unknown code returns 404")
    void stats_unknownCode_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/urls/nosuchcode/stats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- helpers ---------------------------------------------------------

    /** Extract the "code" field from a JSON shorten response string. */
    private static String extractCode(String json) {
        // Simple parse — avoids pulling in a JSON library just for test helpers
        int start = json.indexOf("\"code\":\"") + 8;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
