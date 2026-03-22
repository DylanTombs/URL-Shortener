package com.urlshortener.unit;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.CodeGenerator;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlService")
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private CodeGenerator codeGenerator;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(urlRepository, codeGenerator);
    }

    // ---- shorten() -------------------------------------------------------

    @Test
    @DisplayName("shorten: valid URL returns response with code and shortUrl")
    void shorten_validUrl_returnsResponse() {
        // TODO: implement when UrlService.shorten() exists
    }

    @Test
    @DisplayName("shorten: invalid URL throws IllegalArgumentException")
    void shorten_invalidUrl_throwsException() {
        // TODO: implement
    }

    @Test
    @DisplayName("shorten: ttlDays set → expiresAt is populated")
    void shorten_withTtl_setsExpiresAt() {
        // TODO: implement
    }

    @Test
    @DisplayName("shorten: no ttlDays → expiresAt is null (never expires)")
    void shorten_withoutTtl_expiresAtIsNull() {
        // TODO: implement
    }

    // ---- resolveUrl() ----------------------------------------------------

    @Test
    @DisplayName("resolveUrl: unknown code throws UrlNotFoundException")
    void resolveUrl_unknownCode_throwsNotFoundException() {
        when(urlRepository.findByCode("unknown")).thenReturn(Optional.empty());

        // TODO: uncomment when resolveUrl() is implemented
        // assertThatThrownBy(() -> urlService.resolveUrl("unknown"))
        //         .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    @DisplayName("resolveUrl: expired code throws UrlExpiredException")
    void resolveUrl_expiredCode_throwsExpiredException() {
        ShortenedUrl expired = ShortenedUrl.builder()
                .code("abc12345")
                .longUrl("https://example.com")
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().minusSeconds(60)) // already past
                .clickCount(0)
                .build();

        when(urlRepository.findByCode("abc12345")).thenReturn(Optional.of(expired));

        // TODO: uncomment when resolveUrl() is implemented
        // assertThatThrownBy(() -> urlService.resolveUrl("abc12345"))
        //         .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    @DisplayName("resolveUrl: valid code returns long URL")
    void resolveUrl_validCode_returnsLongUrl() {
        // TODO: implement
    }
}
