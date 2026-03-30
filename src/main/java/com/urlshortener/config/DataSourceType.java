package com.urlshortener.config;

/**
 * Identifies which physical DataSource the routing layer should use.
 *
 * PRIMARY — RDS primary instance; all writes go here.
 * REPLICA  — RDS read replica; all read-only queries go here.
 *            Falls back to PRIMARY if no replica is available (dev with single node).
 */
public enum DataSourceType {
    PRIMARY,
    REPLICA
}
