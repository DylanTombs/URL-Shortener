package com.urlshortener.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Rate limiting configuration using Bucket4j token-bucket algorithm backed by Redis.
 *
 * Architecture:
 *   - Primary defence: AWS WAF (100 req / 5 min per IP at the ALB layer)
 *   - Secondary defence: Bucket4j ProxyManager backed by Redis — state is shared
 *     across all ECS tasks; no in-memory counters that reset on task restarts
 *
 * Limits:
 *   GET /{code}        — 60 requests / minute per IP
 *   POST /api/v1/urls  — 10 requests / minute per IP
 *
 * A dedicated RedisClient is created from @Value properties rather than extracting
 * Spring Data Redis's internal Lettuce client. This avoids lifecycle ordering issues
 * with LettuceConnectionFactory initialization and keeps the rate-limit connection
 * independent of the cache connection.
 */
@Configuration
public class RateLimitConfig {

    static final int REDIRECT_LIMIT = 60;
    static final int CREATE_LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    @Bean(name = "rateLimitRedisClient", destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create(RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(2))
                .build());
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(
            RedisClient rateLimitRedisClient) {
        return rateLimitRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucketProxyManager(
            StatefulRedisConnection<String, byte[]> rateLimitRedisConnection) {
        return LettuceBasedProxyManager.builderFor(rateLimitRedisConnection).build();
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(ProxyManager<String> bucketProxyManager) {
        return new RateLimitInterceptor(bucketProxyManager);
    }

    static BucketConfiguration redirectBucketConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(REDIRECT_LIMIT)
                        .refillGreedy(REDIRECT_LIMIT, WINDOW)
                        .build())
                .build();
    }

    static BucketConfiguration createBucketConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(CREATE_LIMIT)
                        .refillGreedy(CREATE_LIMIT, WINDOW)
                        .build())
                .build();
    }
}
