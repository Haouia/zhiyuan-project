ALTER TABLE doc_db.kb_document_version
    ADD COLUMN parse_claim CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN parse_until TIMESTAMP(6) NULL,
    ADD INDEX ix_parse_queue (status, parse_until, created_at);

CREATE TABLE doc_db.upload_request (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    document_id BIGINT NULL,
    version INT NULL,
    PRIMARY KEY (tenant_id, idempotency_key)
);
