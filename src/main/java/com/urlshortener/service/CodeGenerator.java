package com.urlshortener.service;

import org.springframework.stereotype.Component;

/**
 * Generates short codes for URLs.
 *
 * Strategy: Base62 encoding (a-z A-Z 0-9) of a random 62-bit long.
 * This yields ~8-character codes with 62^8 ≈ 218 trillion unique values,
 * making collisions astronomically unlikely while keeping URLs clean.
 *
 * Alternatives considered and rejected:
 * - UUID: too long, ugly in a URL
 * - Sequential integers: enumerable, exposes volume, attackable
 * - MD5/SHA hash of URL: same URL always produces same code, no TTL variance
 *
 * Collision handling: caller retries with a new random value (see UrlService).
 */
@Component
public class CodeGenerator {

    static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final int CODE_LENGTH = 8;

    /**
     * Generate a random Base62 code of {@value CODE_LENGTH} characters.
     * Stateless and thread-safe — uses a fresh random per call.
     */
    public String generate() {
        // TODO: implement — random 62-bit long → Base62 string
        throw new UnsupportedOperationException("not implemented yet");
    }
}
