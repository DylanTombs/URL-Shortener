package com.urlshortener.dto;

import lombok.Builder;

import java.time.Instant;

/**
 * Response body for GET /api/v1/urls/{code}/stats (HTTP 200).
 */
@Builder
public record StatsResponse(
        String code,
        long clickCount,
        Instant createdAt
) {}
