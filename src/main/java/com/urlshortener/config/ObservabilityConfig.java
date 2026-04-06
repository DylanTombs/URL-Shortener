package com.urlshortener.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tags every metric with the current environment so dev and prod metrics are
 * separable within the same CloudWatch namespace ("UrlShortener").
 *
 * CloudWatch bills per unique metric (name + dimension set), so common tags are
 * kept to a minimum. Only "environment" is added here — per-request dimensions
 * (e.g. per-code tags) are never added to avoid unbounded cardinality.
 */
@Configuration
public class ObservabilityConfig {

    @Value("${spring.profiles.active:local}")
    private String environment;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
                .commonTags("environment", environment);
    }
}
