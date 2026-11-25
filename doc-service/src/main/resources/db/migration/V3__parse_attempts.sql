ALTER TABLE doc_db.kb_document_version
    ADD COLUMN parse_attempts INT NOT NULL DEFAULT 0;
