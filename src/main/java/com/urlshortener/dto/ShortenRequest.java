package com.urlshortener.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Request body for POST /api/v1/urls.
 * ttlDays is optional; when absent the link never expires.
 */
@Builder
public record ShortenRequest(
        @NotBlank(message = "url must not be blank")
        @Size(max = 2048, message = "url must not exceed 2048 characters")
        String url,

        @Positive(message = "ttlDays must be a positive integer")
        @Max(value = 3650, message = "ttlDays must not exceed 3650 (10 years)")
        Integer ttlDays
) {}
