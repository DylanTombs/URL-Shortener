package com.urlshortener.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis cache configuration.
 *
 * Cache TTL strategy:
 *   Default TTL is 24 hours. For URLs with a sooner expiry, the service layer
 *   must evict the cache entry at expiry time (or set a shorter TTL via
 *   CacheManager#getCache + per-entry TTL in Phase 2).
 *   This prevents serving stale expired links from cache.
 *
 * Serialization: values stored as JSON so they are human-readable in Redis CLI
 * and survive application restarts without Java serialization compatibility issues.
 */
@Configuration
public class RedisConfig {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    @Bean
    public RedisCacheConfiguration defaultCacheConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();
    }

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration defaultCacheConfig) {

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfig)
                // Per-cache TTL overrides go here as the project grows
                .withInitialCacheConfigurations(Map.of(
                        "urls", defaultCacheConfig.entryTtl(DEFAULT_TTL)
                ))
                .build();
    }
}
