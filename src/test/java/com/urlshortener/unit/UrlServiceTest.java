package com.urlshortener.unit;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.StatsResponse;
import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.service.CodeGenerator;
import com.urlshortener.service.UrlService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.dao.DataIntegrityViolationException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlService")
class UrlServiceTest {

    private static final String BASE_URL = "https://sho.rt";
    private static final String CODE = "aB3xK9mQ";

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private CodeGenerator codeGenerator;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private ValueOperations<String, String> redisValueOps;

    // SimpleMeterRegistry is an in-memory registry — no external dependencies needed
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(urlRepository, codeGenerator, cacheManager, meterRegistry, redisValueOps);
        // Default: cache returns no hit (miss path) so resolveUrl tests hit the DB.
        // lenient() because shorten/getStats tests don't call resolveUrl() and never touch the cache.
        Cache cache = mock(Cache.class);
        lenient().when(cacheManager.getCache("urls")).thenReturn(cache);
        lenient().when(cache.get(anyString())).thenReturn(null);
    }

    // ---- shorten() -------------------------------------------------------

    @Test
    @DisplayName("shorten: valid URL returns response with code and shortUrl")
    void shorten_validUrl_returnsResponse() {
        when(codeGenerator.generate()).thenReturn(CODE);
        when(urlRepository.existsByCode(CODE)).thenReturn(false);
        when(urlRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = urlService.shorten(
                new ShortenRequest("https://www.example.com/some/long/path", null),
                BASE_URL);

        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.shortUrl()).isEqualTo(BASE_URL + "/" + CODE);
        assertThat(response.expiresAt()).isNull();
    }

    @Test
    @DisplayName("shorten: invalid URL throws InvalidUrlException")
    void shorten_invalidUrl_throwsException() {
        assertThatThrownBy(() ->
                urlService.shorten(new ShortenRequest("not-a-url", null), BASE_URL))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("shorten: ttlDays set → expiresAt is populated")
    void shorten_withTtl_setsExpiresAt() {
        when(codeGenerator.generate()).thenReturn(CODE);
        when(urlRepository.existsByCode(CODE)).thenReturn(false);
        when(urlRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = urlService.shorten(
                new ShortenRequest("https://www.example.com", 7),
                BASE_URL);

        assertThat(response.expiresAt()).isNotNull();
        // expiresAt should be approximately 7 days from now
        Instant sevenDaysFromNow = Instant.now().plusSeconds(7 * 24 * 60 * 60);
        assertThat(response.expiresAt()).isBetween(
                sevenDaysFromNow.minusSeconds(5),
                sevenDaysFromNow.plusSeconds(5));
    }

    @Test
    @DisplayName("shorten: no ttlDays → expiresAt is null (never expires)")
    void shorten_withoutTtl_expiresAtIsNull() {
        when(codeGenerator.generate()).thenReturn(CODE);
        when(urlRepository.existsByCode(CODE)).thenReturn(false);
        when(urlRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = urlService.shorten(
                new ShortenRequest("https://www.example.com", null),
                BASE_URL);

        assertThat(response.expiresAt()).isNull();
    }

    @Test
    @DisplayName("shorten: concurrent DataIntegrityViolationException retries and succeeds")
    void shorten_concurrentCollision_retriesOnConstraintViolation() {
        String firstCode = "aaaaaaaa";
        String secondCode = "bbbbbbbb";
        when(codeGenerator.generate()).thenReturn(firstCode, secondCode);
        when(urlRepository.existsByCode(anyString())).thenReturn(false);
        // First save loses the race — DB unique constraint fires
        when(urlRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = urlService.shorten(
                new ShortenRequest("https://www.example.com", null), BASE_URL);

        assertThat(response.code()).isEqualTo(secondCode);
    }

    @Test
    @DisplayName("shorten: code collision retries until a unique code is found")
    void shorten_codeCollision_retries() {
        String collision = "aaaaaaaa";
        String unique = "bbbbbbbb";
        when(codeGenerator.generate()).thenReturn(collision, unique);
        when(urlRepository.existsByCode(collision)).thenReturn(true);
        when(urlRepository.existsByCode(unique)).thenReturn(false);
        when(urlRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = urlService.shorten(
                new ShortenRequest("https://www.example.com", null),
                BASE_URL);

        assertThat(response.code()).isEqualTo(unique);
    }

    @Test
    @DisplayName("shorten: persists entity with correct fields")
    void shorten_persistsEntityWithCorrectFields() {
        when(codeGenerator.generate()).thenReturn(CODE);
        when(urlRepository.existsByCode(CODE)).thenReturn(false);
        when(urlRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        urlService.shorten(new ShortenRequest("https://www.example.com", null), BASE_URL);

        ArgumentCaptor<ShortenedUrl> captor = ArgumentCaptor.forClass(ShortenedUrl.class);
        verify(urlRepository).save(captor.capture());
        ShortenedUrl saved = captor.getValue();

        assertThat(saved.getCode()).isEqualTo(CODE);
        assertThat(saved.getLongUrl()).isEqualTo("https://www.example.com");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getClickCount()).isZero();
    }

    // ---- resolveUrl() ----------------------------------------------------

    @Test
    @DisplayName("resolveUrl: unknown code throws UrlNotFoundException")
    void resolveUrl_unknownCode_throwsNotFoundException() {
        when(urlRepository.findByCode("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveUrl("unknown"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    @DisplayName("resolveUrl: expired code throws UrlExpiredException")
    void resolveUrl_expiredCode_throwsExpiredException() {
        ShortenedUrl expired = ShortenedUrl.builder()
                .code(CODE)
                .longUrl("https://example.com")
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().minusSeconds(60))
                .clickCount(0)
                .build();

        when(urlRepository.findByCode(CODE)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> urlService.resolveUrl(CODE))
                .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    @DisplayName("resolveUrl: valid non-expired code returns long URL")
    void resolveUrl_validCode_returnsLongUrl() {
        ShortenedUrl url = ShortenedUrl.builder()
                .code(CODE)
                .longUrl("https://example.com/original")
                .createdAt(Instant.now().minusSeconds(60))
                .expiresAt(null)
                .clickCount(5)
                .build();

        when(urlRepository.findByCode(CODE)).thenReturn(Optional.of(url));

        String result = urlService.resolveUrl(CODE);

        assertThat(result).isEqualTo("https://example.com/original");
    }

    @Test
    @DisplayName("resolveUrl: link expiring in the future is still valid")
    void resolveUrl_futureExpiry_isValid() {
        ShortenedUrl url = ShortenedUrl.builder()
                .code(CODE)
                .longUrl("https://example.com")
                .createdAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .clickCount(0)
                .build();

        when(urlRepository.findByCode(CODE)).thenReturn(Optional.of(url));

        assertThat(urlService.resolveUrl(CODE)).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("resolveUrl: URL with no expiry is cached with 24h TTL")
    void resolveUrl_noExpiry_cachedWith24hTtl() {
        ShortenedUrl url = ShortenedUrl.builder()
                .code(CODE).longUrl("https://example.com")
                .createdAt(Instant.now()).expiresAt(null).clickCount(0).build();
        when(urlRepository.findByCode(CODE)).thenReturn(Optional.of(url));

        urlService.resolveUrl(CODE);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisValueOps).set(eq("urls::" + CODE), eq("https://example.com"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("resolveUrl: URL expiring in 5 minutes is cached with ~5m TTL, not 24h")
    void resolveUrl_shortExpiry_cachedWithShortTtl() {
        Instant expiresAt = Instant.now().plusSeconds(300);
        ShortenedUrl url = ShortenedUrl.builder()
                .code(CODE).longUrl("https://example.com")
                .createdAt(Instant.now()).expiresAt(expiresAt).clickCount(0).build();
        when(urlRepository.findByCode(CODE)).thenReturn(Optional.of(url));

        urlService.resolveUrl(CODE);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisValueOps).set(eq("urls::" + CODE), eq("https://example.com"), ttlCaptor.capture());
        // TTL must be well under 24 hours — approximately 5 minutes
        assertThat(ttlCaptor.getValue()).isLessThan(Duration.ofMinutes(6));
        assertThat(ttlCaptor.getValue()).isGreaterThan(Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("computeCacheTtl: null expiry returns 24h")
    void computeCacheTtl_nullExpiry_returns24h() {
        assertThat(UrlService.computeCacheTtl(null)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("computeCacheTtl: expiry further than 24h returns 24h")
    void computeCacheTtl_farFutureExpiry_returns24h() {
        assertThat(UrlService.computeCacheTtl(Instant.now().plus(Duration.ofDays(7))))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("computeCacheTtl: expiry in 1 hour returns ~1h TTL")
    void computeCacheTtl_oneHourExpiry_returnsOneHour() {
        Duration ttl = UrlService.computeCacheTtl(Instant.now().plusSeconds(3600));
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofHours(1));
        assertThat(ttl).isGreaterThan(Duration.ofMinutes(59));
    }

    // ---- getStats() ------------------------------------------------------

    @Test
    @DisplayName("getStats: unknown code throws UrlNotFoundException")
    void getStats_unknownCode_throwsNotFoundException() {
        when(urlRepository.findByCode("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.getStats("unknown"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    @DisplayName("getStats: returns click count and createdAt")
    void getStats_returnsStats() {
        Instant created = Instant.now().minusSeconds(3600);
        ShortenedUrl url = ShortenedUrl.builder()
                .code(CODE)
                .longUrl("https://example.com")
                .createdAt(created)
                .expiresAt(null)
                .clickCount(42)
                .build();

        when(urlRepository.findByCode(CODE)).thenReturn(Optional.of(url));

        StatsResponse stats = urlService.getStats(CODE);

        assertThat(stats.code()).isEqualTo(CODE);
        assertThat(stats.clickCount()).isEqualTo(42);
        assertThat(stats.createdAt()).isEqualTo(created);
    }
}
