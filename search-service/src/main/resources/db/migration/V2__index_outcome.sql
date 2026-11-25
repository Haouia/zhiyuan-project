ALTER TABLE search_db.event_outbox
    ADD UNIQUE KEY uq_version_outcome (tenant_id, document_id, version);
