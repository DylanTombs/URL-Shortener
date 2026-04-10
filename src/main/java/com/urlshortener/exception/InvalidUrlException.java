package com.urlshortener.exception;

/**
 * Thrown when a submitted URL fails semantic validation:
 * missing scheme, missing host, non-http/https scheme, or malformed syntax.
 *
 * Mapped to HTTP 422 Unprocessable Entity by GlobalExceptionHandler.
 * Using a dedicated exception (rather than IllegalArgumentException) prevents
 * framework-thrown IllegalArgumentExceptions from being misclassified as 422.
 */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }

    public InvalidUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
