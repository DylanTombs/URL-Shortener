package com.urlshortener.exception;

/**
 * Thrown when a short code does not exist in the database.
 * Maps to HTTP 404.
 */
public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String code) {
        super("No URL found for code: " + code);
    }
}
