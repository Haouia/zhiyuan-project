package com.ragknowledge.doc;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DocumentList {
    private final JdbcClient jdbc;

    DocumentList(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/internal/v1/documents")
    Page<Document> list(@RequestAttribute(DocumentRequests.TENANT) String tenant,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        PageRequest request = new PageRequest(page, size);
        List<Document> rows = jdbc.sql("""
                SELECT d.id, d.title, d.latest_version, d.active_version, v.status, v.error_code, d.updated_at
                FROM doc_db.kb_document d
                LEFT JOIN doc_db.kb_document_version v ON v.document_id = d.id AND v.version = d.latest_version
                WHERE d.tenant_id = :tenant
                ORDER BY d.id DESC LIMIT :limit OFFSET :offset
                """)
                .param("tenant", tenant)
                .param("limit", request.limit())
                .param("offset", request.offset())
                .query((row, number) -> new Document(row.getString("id"), row.getString("title"),
                        row.getInt("latest_version"), row.getObject("active_version", Integer.class),
                        row.getString("status"), row.getString("error_code"), row.getTimestamp("updated_at").toInstant()))
                .list();
        return request.result(rows);
    }

    record Document(String documentId, String title, int latestVersion, Integer activeVersion,
            String status, String errorCode, Instant updatedAt) {}

    record Page<T>(int page, int size, boolean hasNext, List<T> items) {}

    record PageRequest(int page, int size) {
        PageRequest(String page, String size) {
            this(integer(page), integer(size));
        }

        PageRequest {
            if (page < 0 || size < 1 || size > 100) {
                throw DocumentProblem.badRequest("page 至少为 0，size 必须介于 1 与 100");
            }
        }

        int limit() {
            return size + 1;
        }

        long offset() {
            return (long) page * size;
        }

        <T> Page<T> result(List<T> rows) {
            boolean hasNext = rows.size() > size;
            return new Page<>(page, size, hasNext, hasNext ? rows.subList(0, size) : rows);
        }

        private static int integer(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw DocumentProblem.badRequest("分页参数必须为整数");
            }
        }
    }
}
