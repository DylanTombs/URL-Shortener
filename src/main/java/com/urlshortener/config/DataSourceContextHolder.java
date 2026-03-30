package com.urlshortener.config;

/**
 * Thread-local holder for manual DataSource routing overrides.
 *
 * In normal operation, ReadWriteRoutingDataSource inspects
 * TransactionSynchronizationManager.isCurrentTransactionReadOnly() directly,
 * so this holder is not required. It is available for cases where routing
 * must be controlled outside a Spring-managed transaction boundary.
 *
 * Always call clear() in a finally block to avoid cross-request contamination.
 */
public final class DataSourceContextHolder {

    private static final ThreadLocal<DataSourceType> CONTEXT = new ThreadLocal<>();

    private DataSourceContextHolder() {}

    public static void set(DataSourceType type) {
        CONTEXT.set(type);
    }

    public static DataSourceType get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
