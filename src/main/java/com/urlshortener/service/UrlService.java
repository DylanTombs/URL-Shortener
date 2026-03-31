package com.urlshortener.service;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.StatsResponse;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
 *   - Only the redirect path is cached — cache-aside on reads only.
 *   - @Cacheable is replaced with explicit cache logic so we can observe
 *     cache hits vs misses and publish the cache_hit tag on url.redirect.
 *   - Cache TTL is managed by RedisConfig (24h default).
 *
 * Metrics emitted:
 *   url.created         Counter   — after successful shorten()
 *   url.redirect        Timer     — wraps resolveUrl(), tagged cache_hit=true|false
 *   url.not_found       Counter   — when UrlNotFoundException is thrown
 *   url.expired         Counter   — when UrlExpiredException is thrown
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {

    private static final int MAX_COLLISION_RETRIES = 5;
    private static final String CACHE_NAME = "urls";

    private final UrlRepository urlRepository;
    private final CodeGenerator codeGenerator;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;

    /**
     * Create a new shortened URL.
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

        meterRegistry.counter("url.created").increment();
        log.info("url_shortened code={} ttlDays={}", saved.getCode(), request.ttlDays());

        return ShortenResponse.builder()
                .code(saved.getCode())
                .shortUrl(baseUrl + "/" + saved.getCode())
                .expiresAt(saved.getExpiresAt())
                .build();
    }

    /**
     * Resolve a short code to its original URL.
     *
     * Explicit cache logic (replaces @Cacheable) so cache hit/miss can be observed.
     * Timer tag cache_hit=true|false drives the cache hit rate alarm and dashboard widget.
     */
    @Transactional(readOnly = true)
    public String resolveUrl(String code) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean cacheHit = false;
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                Cache.ValueWrapper cached = cache.get(code);
                if (cached != null) {
                    cacheHit = true;
                    return (String) cached.get();
                }
            }

            ShortenedUrl url = urlRepository.findByCode(code)
                    .orElseThrow(() -> {
                        meterRegistry.counter("url.not_found").increment();
                        return new UrlNotFoundException(code);
                    });

            if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
                meterRegistry.counter("url.expired").increment();
                throw new UrlExpiredException(code);
            }

            if (cache != null) {
                cache.put(code, url.getLongUrl());
            }

            log.info("url_resolved code={} cacheHit=false", code);
            return url.getLongUrl();

        } finally {
            sample.stop(Timer.builder("url.redirect")
                    .tag("cache_hit", String.valueOf(cacheHit))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry));
        }
    }

    /**
     * Increment the click counter for a short code.
     * Separate @Transactional (write) so it always routes to the primary DataSource,
     * even when resolveUrl() was served from Redis cache.
     */
    @Transactional
    public void incrementClickCount(String code) {
        urlRepository.incrementClickCount(code);
    }

    /**
     * Return click statistics for a short code.
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
