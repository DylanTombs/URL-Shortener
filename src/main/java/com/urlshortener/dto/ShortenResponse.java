package com.urlshortener.dto;

import lombok.Builder;

import java.time.Instant;

/**
 * Response body for POST /api/v1/urls (HTTP 201).
 */
@Builder
public record ShortenResponse(
        String code,
        String shortUrl,
        Instant expiresAt
) {}
