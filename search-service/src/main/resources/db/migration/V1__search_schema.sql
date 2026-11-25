CREATE TABLE search_db.doc_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id BIGINT NOT NULL,
    version INT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text LONGTEXT NOT NULL,
    locator_unit VARCHAR(16) NOT NULL,
    start_position INT NOT NULL,
    end_position INT NOT NULL,
    embedding_model_version VARCHAR(128) NOT NULL,
    UNIQUE KEY uq_chunk_version (tenant_id, document_id, version, chunk_index),
    CHECK (version > 0 AND chunk_index >= 0),
    CHECK (locator_unit IN ('paragraph', 'page', 'line')),
    CHECK (start_position > 0 AND end_position >= start_position)
);

CREATE TABLE search_db.event_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id BIGINT NOT NULL,
    version INT NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    INDEX ix_outbox_unpublished (published_at, created_at)
);
