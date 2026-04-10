-- The UNIQUE constraint on `code` automatically creates a B-tree index in PostgreSQL.
-- The explicit idx_shortened_urls_code index is identical to the constraint index —
-- it wastes storage, slows writes (two index updates per insert), and misleads
-- anyone reading the schema into thinking two separate structures exist.
DROP INDEX IF EXISTS idx_shortened_urls_code;
