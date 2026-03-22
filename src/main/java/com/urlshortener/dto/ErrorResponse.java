package com.urlshortener.dto;

import lombok.Builder;

/**
 * Consistent error envelope returned for all 4xx/5xx responses.
 * Keeps error messages uniform across the API surface.
 */
@Builder
public record ErrorResponse(
        String error,
        String message
) {}
