package com.ragknowledge.doc;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/internal/v1/documents")
class DocumentVersions {
    private static final String VERSION_SQL = """
            SELECT d.id, d.active_version, v.version, v.filename, v.content_type, v.status, v.error_code,
                   v.created_at, v.updated_at, OCTET_LENGTH(v.original_bytes) AS byte_size
            FROM doc_db.kb_document d JOIN doc_db.kb_document_version v ON v.document_id = d.id
            WHERE d.tenant_id = :tenant AND d.id = :id
            """;

    private final DocumentUploads uploads;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    DocumentVersions(DocumentUploads uploads, JdbcClient jdbc, JsonMapper json) {
        this.uploads = uploads;
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostMapping(consumes = "multipart/form-data")
    ResponseEntity<DocumentUploads.Accepted> create(@RequestAttribute(DocumentRequests.TENANT) String tenant,
            @RequestHeader("Idempotency-Key") String key, @RequestParam String title, @RequestParam MultipartFile file) {
        title = title.strip();
        if (title.isEmpty() || title.codePointCount(0, title.length()) > 255 || title.chars().anyMatch(Character::isISOControl)) {
            throw DocumentProblem.badRequest("文档标题应为 1 到 255 个字符");
        }
        return ResponseEntity.accepted().body(uploads.accept(tenant, key, null, title, file));
    }

    @PostMapping(path = "/{id}/versions", consumes = "multipart/form-data")
    ResponseEntity<DocumentUploads.Accepted> append(@RequestAttribute(DocumentRequests.TENANT) String tenant,
            @PathVariable long id, @RequestHeader("Idempotency-Key") String key, @RequestParam MultipartFile file) {
        return ResponseEntity.accepted().body(uploads.accept(tenant, key, id, "", file));
    }

    @GetMapping("/{id}/versions")
    DocumentList.Page<Version> history(@RequestAttribute(DocumentRequests.TENANT) String tenant, @PathVariable long id,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        DocumentList.PageRequest request = new DocumentList.PageRequest(page, size);
        if (jdbc.sql("SELECT id FROM doc_db.kb_document WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant).param("id", id).query(Long.class).optional().isEmpty()) {
            throw DocumentProblem.notFound();
        }
        List<Version> rows = jdbc.sql(VERSION_SQL + " ORDER BY v.version DESC LIMIT :limit OFFSET :offset")
                .param("tenant", tenant).param("id", id).param("limit", request.limit()).param("offset", request.offset())
                .query(this::version).list();
        return request.result(rows);
    }

    @GetMapping("/{id}/versions/{number}")
    Version status(@RequestAttribute(DocumentRequests.TENANT) String tenant, @PathVariable long id, @PathVariable int number) {
        return jdbc.sql(VERSION_SQL + " AND v.version = :version")
                .param("tenant", tenant).param("id", id).param("version", number)
                .query(this::version).optional().orElseThrow(DocumentProblem::notFound);
    }

    @GetMapping("/{id}/versions/{number}/parsed")
    ParsedVersion parsed(@RequestAttribute(DocumentRequests.SEARCH_TENANT) String tenant, @PathVariable long id, @PathVariable int number) {
        return jdbc.sql("""
                SELECT d.title, v.parsed_text, v.positions
                FROM doc_db.kb_document d JOIN doc_db.kb_document_version v ON v.document_id = d.id
                WHERE d.tenant_id = :tenant AND d.id = :id AND v.version = :version
                  AND v.status IN ('INDEXING', 'READY') AND v.parsed_text IS NOT NULL
                """)
                .param("tenant", tenant).param("id", id).param("version", number)
                .query((row, index) -> new ParsedVersion(Long.toString(id), row.getString("title"), number,
                        row.getString("parsed_text"), json.readTree(row.getString("positions"))))
                .optional().orElseThrow(DocumentProblem::notFound);
    }

    @GetMapping("/{id}/versions/{number}/file")
    ResponseEntity<byte[]> download(@RequestAttribute(DocumentRequests.TENANT) String tenant, @PathVariable long id,
            @PathVariable int number) {
        OriginalFile file = jdbc.sql("""
                SELECT v.filename, v.content_type, v.original_bytes
                FROM doc_db.kb_document d JOIN doc_db.kb_document_version v ON v.document_id = d.id
                WHERE d.tenant_id = :tenant AND d.id = :id AND v.version = :version
                """)
                .param("tenant", tenant).param("id", id).param("version", number)
                .query((row, index) -> new OriginalFile(row.getString("filename"), row.getString("content_type"),
                        row.getBytes("original_bytes")))
                .optional().orElseThrow(DocumentProblem::notFound);
        return ResponseEntity.ok()
                .contentType(mediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }

    private static MediaType mediaType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException error) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private Version version(ResultSet row, int index) throws SQLException {
        long byteSize = row.getLong("byte_size");
        Long size = row.wasNull() ? null : byteSize;
        return new Version(row.getString("id"), row.getInt("version"), row.getString("filename"),
                row.getString("status"), row.getObject("active_version", Integer.class), row.getString("error_code"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getString("content_type"), size);
    }

    record Version(String documentId, int version, String filename, String status, Integer activeVersion,
            String errorCode, Instant createdAt, Instant updatedAt, String contentType, Long byteSize) {}

    record OriginalFile(String filename, String contentType, byte[] bytes) {}

    record ParsedVersion(String documentId, String title, int version, String text, JsonNode locations) {}
}
