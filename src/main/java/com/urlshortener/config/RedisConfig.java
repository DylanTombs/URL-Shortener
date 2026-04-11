package com.urlshortener.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis cache configuration.
 *
 * Serialization: both keys and values use StringRedisSerializer.
 *   - Values cached in the "urls" cache are always plain String (the longUrl).
 *   - GenericJackson2JsonRedisSerializer embeds Java type metadata
 *     (["java.lang.String","https://..."]) which wastes storage, breaks redis-cli
 *     inspection, and couples deserialization to the Java type name.
 *   - StringRedisSerializer stores the raw string — readable, compact, no type coupling.
 *
 * Cache TTL strategy:
 *   Default TTL is 24 hours. For URLs with a sooner expiry, UrlService writes via
 *   StringRedisTemplate.setex() to apply a per-entry TTL = min(24h, time-to-expiry).
 *   This prevents serving a stale redirect after the link expires.
 *
 * Note: deploying this change requires flushing any existing cache entries written
 * by the old Jackson serializer (see RUNBOOK.md §Redis Cache Flush).
 */
@Configuration
@EnableCaching
public class RedisConfig {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Exposes ValueOperations as a bean so UrlService can write per-entry cache TTLs.
     * ValueOperations is an interface — cleanly mockable in unit tests without inline-mock
     * bytecode instrumentation required for the concrete StringRedisTemplate class.
     */
    @Bean
    public ValueOperations<String, String> redisValueOps(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory).opsForValue();
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration urlsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(urlsConfig)
                .withInitialCacheConfigurations(Map.of("urls", urlsConfig))
                .build();
    }
}
