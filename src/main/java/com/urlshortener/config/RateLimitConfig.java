package com.urlshortener.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

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
 * The ProxyManager stores each bucket in Redis under the key "clientIp:routeKey".
 * A dedicated Lettuce connection (separate channel, shared event loop) is used to
 * avoid interfering with Spring's managed connection pool.
 */
@Configuration
public class RateLimitConfig {

    static final int REDIRECT_LIMIT = 60;
    static final int CREATE_LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(
            LettuceConnectionFactory lettuceConnectionFactory) {
        if (!(lettuceConnectionFactory.getNativeClient() instanceof RedisClient client)) {
            throw new IllegalStateException(
                    "Expected standalone RedisClient but got: "
                    + lettuceConnectionFactory.getNativeClient());
        }
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
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
