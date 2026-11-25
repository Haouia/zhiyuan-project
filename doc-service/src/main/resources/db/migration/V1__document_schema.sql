CREATE TABLE doc_db.kb_document (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    latest_version INT NOT NULL,
    active_version INT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX ix_document_tenant (tenant_id, id),
    CHECK (latest_version > 0),
    CHECK (active_version IS NULL OR active_version BETWEEN 1 AND latest_version)
);

CREATE TABLE doc_db.kb_document_version (
    document_id BIGINT NOT NULL,
    version INT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    original_bytes MEDIUMBLOB NOT NULL,
    parsed_text LONGTEXT NULL,
    positions JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (document_id, version),
    FOREIGN KEY (document_id) REFERENCES doc_db.kb_document (id),
    CHECK (version > 0),
    CHECK (OCTET_LENGTH(original_bytes) BETWEEN 1 AND 10485760),
    CHECK (status IN ('PENDING', 'PARSING', 'INDEXING', 'READY', 'FAILED'))
);

CREATE TABLE doc_db.event_outbox (
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
