package com.urlshortener.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Manual DataSource configuration replacing Spring Boot's auto-configured DataSource.
 *
 * Two HikariCP pools are created — primary (writes) and replica (reads) — and
 * wired into a ReadWriteRoutingDataSource. The routing source is wrapped in
 * LazyConnectionDataSourceProxy so that Hibernate defers physical connection
 * acquisition until the first SQL statement, after the @Transactional AOP has
 * set the read-only flag on TransactionSynchronizationManager.
 *
 * The @Primary bean is the lazy proxy; Spring's JPA, transaction management,
 * and Flyway auto-configurations all pick it up automatically.
 *
 * Requires: @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
 * on the main class to prevent a conflicting auto-configured DataSource.
 */
@Configuration
public class DataSourceConfig {

    // ---- Primary DataSource (writes) -------------------------------------

    @Bean(name = "primaryDataSource", destroyMethod = "close")
    public HikariDataSource primaryDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("HikariPrimary");
        ds.setMaximumPoolSize(20);
        ds.setMinimumIdle(5);
        ds.setConnectionTimeout(30_000);
        ds.setIdleTimeout(600_000);
        ds.setMaxLifetime(1_800_000);
        return ds;
    }

    // ---- Replica DataSource (reads) --------------------------------------

    @Bean(name = "replicaDataSource", destroyMethod = "close")
    public HikariDataSource replicaDataSource(
            @Value("${spring.datasource.replica.url}") String url,
            @Value("${spring.datasource.replica.username}") String username,
            @Value("${spring.datasource.replica.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("HikariReplica");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30_000);
        ds.setIdleTimeout(600_000);
        ds.setMaxLifetime(1_800_000);
        return ds;
    }

    // ---- Routing + lazy proxy (the @Primary DataSource) ------------------

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("primaryDataSource") HikariDataSource primaryDataSource,
            @Qualifier("replicaDataSource") HikariDataSource replicaDataSource) {

        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                DataSourceType.PRIMARY, primaryDataSource,
                DataSourceType.REPLICA, replicaDataSource
        ));
        routing.setDefaultTargetDataSource(primaryDataSource);
        routing.afterPropertiesSet();

        // CRITICAL: LazyConnectionDataSourceProxy MUST wrap the routing source.
        //
        // Without it, Hibernate acquires a physical connection at transaction open
        // time — before @Transactional AOP has a chance to call
        // TransactionSynchronizationManager.setCurrentTransactionReadOnly(true).
        // ReadWriteRoutingDataSource.determineCurrentLookupKey() then reads a stale
        // readOnly=false flag and routes ALL traffic to the primary, silently.
        //
        // The failure mode is invisible: the app starts, all tests pass (because both
        // datasources point to the same container in the test environment), and all
        // reads hit the primary in production until someone notices the replica is idle.
        //
        // ReadReplicaRoutingTest verifies the routing logic in isolation. The
        // integration tests verify end-to-end behaviour with both datasources wired.
        // Do not remove this wrapper.
        return new LazyConnectionDataSourceProxy(routing);
    }
}
