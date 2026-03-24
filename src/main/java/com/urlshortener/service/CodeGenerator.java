package com.urlshortener.service;

/**
 * Contract for generating short codes.
 *
 * Default implementation: {@link Base62CodeGenerator} — Base62 encoding
 * (a-z A-Z 0-9) of a SecureRandom value, producing {@value CODE_LENGTH}-char codes
 * with 62^8 ≈ 218 trillion unique values.
 *
 * Defined as an interface so UrlService can be unit-tested with a mock
 * without requiring byte-buddy subclassing of a concrete class.
 */
public interface CodeGenerator {

    String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    int CODE_LENGTH = 8;

    /** Generate a random unique short code. */
    String generate();
}
