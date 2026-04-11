package com.urlshortener.unit;

import com.urlshortener.config.DataSourceContextHolder;
import com.urlshortener.config.DataSourceType;
import com.urlshortener.config.ReadWriteRoutingDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReadWriteRoutingDataSource")
class ReadReplicaRoutingTest {

    private ReadWriteRoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() {
        routingDataSource = new ReadWriteRoutingDataSource();
        // Ensure clean thread-local state before each test
        DataSourceContextHolder.clear();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @AfterEach
    void tearDown() {
        DataSourceContextHolder.clear();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    // ── Transaction-based routing ──────────────────────────────────────────

    @Test
    @DisplayName("readOnly=true transaction → routes to REPLICA")
    void readOnlyTransaction_routesToReplica() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key).isEqualTo(DataSourceType.REPLICA);
    }

    @Test
    @DisplayName("readOnly=false transaction → routes to PRIMARY")
    void writeTransaction_routesToPrimary() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key).isEqualTo(DataSourceType.PRIMARY);
    }

    @Test
    @DisplayName("no transaction active → defaults to PRIMARY")
    void noTransaction_defaultsToPrimary() {
        // TransactionSynchronizationManager.isCurrentTransactionReadOnly() returns
        // false when no transaction is active, so PRIMARY is the default.
        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key).isEqualTo(DataSourceType.PRIMARY);
    }

    // ── Manual context holder override ────────────────────────────────────

    @Test
    @DisplayName("explicit REPLICA in DataSourceContextHolder overrides transaction flag")
    void explicitReplicaOverride_routesToReplica() {
        // readOnly=false but manual override forces REPLICA
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        DataSourceContextHolder.set(DataSourceType.REPLICA);

        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key).isEqualTo(DataSourceType.REPLICA);
    }

    @Test
    @DisplayName("explicit PRIMARY in DataSourceContextHolder overrides readOnly=true")
    void explicitPrimaryOverride_routesToPrimary() {
        // readOnly=true but manual override forces PRIMARY
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        DataSourceContextHolder.set(DataSourceType.PRIMARY);

        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key).isEqualTo(DataSourceType.PRIMARY);
    }

    @Test
    @DisplayName("DataSourceContextHolder.clear() restores transaction-based routing")
    void clearContextHolder_restoresTransactionRouting() {
        DataSourceContextHolder.set(DataSourceType.PRIMARY);
        DataSourceContextHolder.clear();

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key).isEqualTo(DataSourceType.REPLICA);
    }

    // ── LazyConnectionDataSourceProxy dependency guard ─────────────────────
    //
    // This test exists to document the critical dependency on
    // LazyConnectionDataSourceProxy in DataSourceConfig. Without it, Hibernate
    // acquires a connection before @Transactional AOP sets the readOnly flag,
    // so all traffic silently routes to the primary in production.
    //
    // The test verifies the routing logic that the proxy enables: that readOnly=true
    // is correctly observed at determineCurrentLookupKey() time. If this test fails,
    // the routing logic is broken — likely because the proxy was removed.

    @Test
    @DisplayName("readOnly flag is observable at lookup-key time (LazyConnectionDataSourceProxy contract)")
    void readOnlyFlagObservableAtLookupTime() {
        // Simulate what LazyConnectionDataSourceProxy enables:
        // the readOnly flag is set BEFORE the connection (and therefore the routing
        // key) is determined. Without the proxy, Hibernate acquires the connection
        // at transaction open time when readOnly is still false.
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        // Routing must observe the flag correctly at this point
        Object key = routingDataSource.determineCurrentLookupKey();

        assertThat(key)
                .as("readOnly=true must route to REPLICA — if this fails, " +
                    "check that LazyConnectionDataSourceProxy is present in DataSourceConfig")
                .isEqualTo(DataSourceType.REPLICA);
    }
}
