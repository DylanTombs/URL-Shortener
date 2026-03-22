package com.urlshortener.service;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.StatsResponse;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // TODO: implement
        //   1. Validate URL format (java.net.URI)
        //   2. Generate code with collision retry loop (max 5 attempts)
        //   3. Compute expiresAt from ttlDays (null if absent)
        //   4. Persist ShortenedUrl entity
        //   5. Return ShortenResponse
        throw new UnsupportedOperationException("not implemented yet");
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
        // TODO: implement
        //   1. Lookup by code (read replica via routing in Phase 2)
        //   2. Throw UrlNotFoundException if absent
        //   3. Check expiresAt — throw UrlExpiredException if past
        //   4. Increment click_count (write to primary)
        //   5. Return longUrl
        throw new UnsupportedOperationException("not implemented yet");
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
        // TODO: implement
        throw new UnsupportedOperationException("not implemented yet");
    }
}
