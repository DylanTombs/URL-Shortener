package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

/**
 * Request body for POST /api/v1/urls.
 * ttlDays is optional; when absent the link never expires.
 */
@Builder
public record ShortenRequest(
        @NotBlank(message = "url must not be blank")
        String url,

        @Positive(message = "ttlDays must be a positive integer")
        Integer ttlDays
) {}
