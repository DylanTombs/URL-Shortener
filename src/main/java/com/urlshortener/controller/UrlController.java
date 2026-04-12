package com.urlshortener.controller;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.StatsResponse;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer. No business logic lives here — controllers are thin by design.
 *
 * Endpoints:
 *   POST /api/v1/urls               — shorten a URL
 *   GET  /{code}                    — redirect to original URL
 *   GET  /api/v1/urls/{code}/stats  — fetch click statistics
 *
 * @Validated enables method-level constraint processing on @PathVariable parameters.
 * Violations throw ConstraintViolationException, handled by GlobalExceptionHandler.
 */
@Validated
@RestController
@RequiredArgsConstructor
@Slf4j
public class UrlController {

    private static final String CODE_PATTERN = "[a-zA-Z0-9]+";
    private static final int CODE_MAX_LENGTH = 12;

    @Value("${app.base-url}")
    private String baseUrl;

    private final UrlService urlService;

    /**
     * Shorten a URL.
     * Returns 201 Created with the generated short code and optional expiry.
     */
    @PostMapping("/api/v1/urls")
    public ResponseEntity<ShortenResponse> shorten(
            @Valid @RequestBody ShortenRequest request) {

        ShortenResponse response = urlService.shorten(request, baseUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Redirect to the original URL.
     * Returns 302 Found — browser does not cache, ensuring every click reaches the
     * service. This is required for accurate click counting, cache hit metrics, and
     * correct expiry enforcement. A 301 would be cached permanently by browsers,
     * making click counts, expiry, and cache metrics unreliable after the first visit.
     * See DECISIONS.md Decision 8.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(
            @PathVariable
            @Size(max = CODE_MAX_LENGTH, message = "code must be at most 12 characters")
            @Pattern(regexp = CODE_PATTERN, message = "code must be alphanumeric")
            String code) {

        String longUrl = urlService.resolveUrl(code);
        // Isolated catch: a DB write failure must not convert a successful redirect into
        // a 500. The redirect has already been determined; counting is best-effort.
        try {
            urlService.incrementClickCount(code);
        } catch (Exception e) {
            log.error("click_count_increment_failed code={}", code, e);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, longUrl)
                .build();
    }

    /**
     * Fetch click statistics for a short code.
     */
    @GetMapping("/api/v1/urls/{code}/stats")
    public ResponseEntity<StatsResponse> stats(
            @PathVariable
            @Size(max = CODE_MAX_LENGTH, message = "code must be at most 12 characters")
            @Pattern(regexp = CODE_PATTERN, message = "code must be alphanumeric")
            String code) {

        return ResponseEntity.ok(urlService.getStats(code));
    }
}
