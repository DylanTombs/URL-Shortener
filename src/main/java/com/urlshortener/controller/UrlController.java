package com.urlshortener.controller;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.StatsResponse;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * HTTP layer. No business logic lives here — controllers are thin by design.
 *
 * Endpoints:
 *   POST /api/v1/urls          — shorten a URL
 *   GET  /{code}               — redirect to original URL
 *   GET  /api/v1/urls/{code}/stats — fetch click statistics
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class UrlController {

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
     * Returns 301 Moved Permanently — browsers cache this, reducing load.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String longUrl = urlService.resolveUrl(code);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, longUrl)
                .build();
    }

    /**
     * Fetch click statistics for a short code.
     */
    @GetMapping("/api/v1/urls/{code}/stats")
    public ResponseEntity<StatsResponse> stats(@PathVariable String code) {
        return ResponseEntity.ok(urlService.getStats(code));
    }
}
