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
}
