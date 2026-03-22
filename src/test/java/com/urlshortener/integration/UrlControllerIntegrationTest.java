package com.urlshortener.integration;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests using real Postgres and Redis via Testcontainers.
 * No H2, no mocks — this exercises the full application stack.
 *
 * Container lifecycle: @Testcontainers + static containers = shared across
 * all test methods, started once per test class (fast).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("UrlController Integration")
class UrlControllerIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;

    // ---- POST /api/v1/urls -----------------------------------------------

    @Test
    @DisplayName("POST /api/v1/urls: valid request returns 201")
    void shorten_validRequest_returns201() throws Exception {
        // TODO: implement when business logic is in place
        // mockMvc.perform(post("/api/v1/urls")
        //         .contentType(MediaType.APPLICATION_JSON)
        //         .content("""
        //                 {"url": "https://www.example.com/some/long/path"}
        //                 """))
        //         .andExpect(status().isCreated())
        //         .andExpect(jsonPath("$.code").isNotEmpty())
        //         .andExpect(jsonPath("$.shortUrl").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/urls: blank URL returns 422")
    void shorten_blankUrl_returns422() throws Exception {
        // TODO: implement
    }

    // ---- GET /{code} -----------------------------------------------------

    @Test
    @DisplayName("GET /{code}: known code returns 301 redirect")
    void redirect_knownCode_returns301() throws Exception {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /{code}: unknown code returns 404")
    void redirect_unknownCode_returns404() throws Exception {
        // TODO: implement when resolveUrl() is implemented
        // mockMvc.perform(get("/nonexistent"))
        //         .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{code}: expired code returns 410")
    void redirect_expiredCode_returns410() throws Exception {
        // TODO: implement
    }

    // ---- GET /api/v1/urls/{code}/stats -----------------------------------

    @Test
    @DisplayName("GET /api/v1/urls/{code}/stats: known code returns 200 with stats")
    void stats_knownCode_returns200() throws Exception {
        // TODO: implement
    }
}
