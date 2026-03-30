package com.urlshortener.service;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.StatsResponse;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Core business logic for creating and resolving short URLs.
 *
 * Cache strategy:
 *   - Only the redirect path is cached (@Cacheable on resolveUrl).
 *   - Write path is never cached — cache-aside on reads only.
 *   - Cache TTL = min(24h, time-to-expiry) so stale expired links are never served.
 *     TTL is set in RedisConfig via RedisCacheConfiguration per-cache config.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {

    private static final int MAX_COLLISION_RETRIES = 5;

    private final UrlRepository urlRepository;
    private final CodeGenerator codeGenerator;

    /**
     * Create a new shortened URL.
     *
     * @param request validated shorten request
     * @param baseUrl the public base URL (e.g. "https://sho.rt")
     * @return response with generated code, full short URL, and optional expiry
     */
    @Transactional
    public ShortenResponse shorten(ShortenRequest request, String baseUrl) {
        validateUrl(request.url());

        String code = generateUniqueCode();
        Instant expiresAt = request.ttlDays() != null
                ? Instant.now().plus(request.ttlDays(), ChronoUnit.DAYS)
                : null;

        ShortenedUrl entity = ShortenedUrl.builder()
                .code(code)
                .longUrl(request.url())
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .clickCount(0)
                .build();
        ShortenedUrl saved = urlRepository.save(Objects.requireNonNull(entity));

        log.info("url_shortened code={} ttlDays={}", saved.getCode(), request.ttlDays());

        return ShortenResponse.builder()
                .code(saved.getCode())
                .shortUrl(baseUrl + "/" + saved.getCode())
                .expiresAt(saved.getExpiresAt())
                .build();
    }

    /**
     * Resolve a short code to its original URL.
     * Result is cached in Redis under cache name "urls".
     *
     * @param code the short code
     * @return the original long URL
     * @throws UrlNotFoundException if code does not exist
     * @throws UrlExpiredException  if code exists but TTL has passed
     */
    @Cacheable(cacheNames = "urls", key = "#code")
    @Transactional(readOnly = true)
    public String resolveUrl(String code) {
        ShortenedUrl url = urlRepository.findByCode(code)
                .orElseThrow(() -> new UrlNotFoundException(code));

        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
            throw new UrlExpiredException(code);
        }

        log.info("url_resolved code={}", code);
        return url.getLongUrl();
    }

    /**
     * Return click statistics for a short code.
     *
     * @param code the short code
     * @return stats including click count and creation time
     * @throws UrlNotFoundException if code does not exist
     */
    @Transactional(readOnly = true)
    public StatsResponse getStats(String code) {
        ShortenedUrl url = urlRepository.findByCode(code)
                .orElseThrow(() -> new UrlNotFoundException(code));

        return StatsResponse.builder()
                .code(url.getCode())
                .clickCount(url.getClickCount())
                .createdAt(url.getCreatedAt())
                .build();
    }

    /**
     * Increment the click counter for a short code.
     * Runs as a write transaction — routes to the primary DataSource.
     * Called separately from resolveUrl so it always fires, even on cache hits.
     *
     * @param code the short code that was resolved
     */
    @Transactional
    public void incrementClickCount(String code) {
        urlRepository.incrementClickCount(code);
    }

    // ---- private helpers -------------------------------------------------

    private void validateUrl(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Invalid URL: missing scheme or host: " + url);
            }
            if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
                throw new IllegalArgumentException("Invalid URL: scheme must be http or https: " + url);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
    }

    private String generateUniqueCode() {
        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String code = codeGenerator.generate();
            if (!urlRepository.existsByCode(code)) {
                return code;
            }
            log.warn("code_collision attempt={} code={}", attempt, code);
        }
        throw new IllegalStateException("Failed to generate unique code after " + MAX_COLLISION_RETRIES + " attempts");
    }
}
