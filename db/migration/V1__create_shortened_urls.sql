-- Phase 1: initial schema
-- Flyway convention: V<version>__<description>.sql

CREATE TABLE IF NOT EXISTS shortened_urls (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(12)  UNIQUE NOT NULL,
    long_url    TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ,
    click_count BIGINT       NOT NULL DEFAULT 0
);

-- Primary lookup index: code is the hot path for every redirect.
CREATE INDEX idx_shortened_urls_code
    ON shortened_urls (code);

-- Partial index for TTL cleanup jobs: only rows that expire need it.
-- This is intentionally a partial index — non-expiring rows are excluded,
-- keeping the index small and the cleanup query fast.
CREATE INDEX idx_shortened_urls_expires_at
    ON shortened_urls (expires_at)
    WHERE expires_at IS NOT NULL;
