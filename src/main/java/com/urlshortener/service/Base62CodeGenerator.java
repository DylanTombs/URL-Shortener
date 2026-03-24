package com.urlshortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Base62 implementation of {@link CodeGenerator}.
 *
 * Each character is chosen by drawing a random index into the 62-char
 * alphabet. SecureRandom is used so codes are unpredictable and cannot
 * be guessed from observing previous codes.
 *
 * Thread-safe: SecureRandom is thread-safe after construction.
 */
@Component
public class Base62CodeGenerator implements CodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
