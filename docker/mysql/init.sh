#!/bin/bash
(
    set -euo pipefail
    if ! [[ ${RAG_DOC_DB_PASSWORD:-} =~ ^[0-9a-f]{64}$ && ${RAG_SEARCH_DB_PASSWORD:-} =~ ^[0-9a-f]{64}$ ]]; then
        echo 'MySQL service passwords must be 64 lowercase hex characters' >&2
        exit 1
    fi

    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<SQL
CREATE DATABASE doc_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE search_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'rag_doc'@'%' IDENTIFIED BY '${RAG_DOC_DB_PASSWORD}';
CREATE USER 'rag_search'@'%' IDENTIFIED BY '${RAG_SEARCH_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON doc_db.* TO 'rag_doc'@'%';
GRANT ALL PRIVILEGES ON search_db.* TO 'rag_search'@'%';
SQL
)
