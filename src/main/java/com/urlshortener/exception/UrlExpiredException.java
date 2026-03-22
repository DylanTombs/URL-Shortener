package com.urlshortener.exception;

/**
 * Thrown when a short code exists but its TTL has passed.
 * Maps to HTTP 410 Gone — the resource existed but is no longer available.
 */
public class UrlExpiredException extends RuntimeException {

    public UrlExpiredException(String code) {
        super("URL has expired for code: " + code);
    }
}
