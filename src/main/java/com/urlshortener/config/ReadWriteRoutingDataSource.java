package com.urlshortener.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes database connections to the primary or replica DataSource based on
 * the current transaction's read-only flag.
 *
 * Key insight — why LazyConnectionDataSourceProxy is required:
 *   Without it, Hibernate eagerly acquires a physical connection at the start of
 *   the Hibernate session (before AOP has processed @Transactional(readOnly=true)).
 *   LazyConnectionDataSourceProxy defers connection acquisition until the first
 *   SQL statement, by which time the transaction interceptor has already set the
 *   read-only flag on TransactionSynchronizationManager.
 *
 * Routing decision:
 *   readOnly=true  → REPLICA  (all SELECT queries from GET /{code} and /stats)
 *   readOnly=false → PRIMARY  (all INSERT/UPDATE from POST /api/v1/urls and click count)
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // Prefer explicit context override (for manual routing outside transactions)
        DataSourceType explicit = DataSourceContextHolder.get();
        if (explicit != null) {
            return explicit;
        }
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? DataSourceType.REPLICA
                : DataSourceType.PRIMARY;
    }
}
