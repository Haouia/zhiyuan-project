package com.ragknowledge.doc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/internal/v1/documents")
class DocumentSources {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentSources.class);
    private static final Set<String> UNITS = Set.of("line", "page", "paragraph");
    private final JdbcClient jdbc;
    private final JsonMapper json;

    DocumentSources(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostMapping("/active-versions")
    ActiveVersions activeVersions(@RequestAttribute(DocumentRequests.SEARCH_TENANT) String tenant,
            @RequestBody DocumentIds request) {
        if (request == null || request.documentIds() == null || request.documentIds().isEmpty() || request.documentIds().size() > 400) {
            throw DocumentProblem.badRequest("文档 ID 数量应为 1 到 400");
        }
        List<Long> ids = request.documentIds().stream().map(this::documentId).distinct().toList();
        Map<String, Integer> versions = jdbc.sql("""
                SELECT d.id, d.active_version
                FROM doc_db.kb_document d JOIN doc_db.kb_document_version v
                    ON v.document_id = d.id AND v.version = d.active_version
                WHERE d.tenant_id = :tenant AND d.id IN (:ids) AND v.status = 'READY'
                """)
                .param("tenant", tenant).param("ids", ids)
                .query(rows -> {
                    var result = new HashMap<String, Integer>();
                    while (rows.next()) {
                        result.put(rows.getString("id"), rows.getInt("active_version"));
                    }
                    return result;
                });
        return new ActiveVersions(versions);
    }

    @GetMapping("/{id}/versions/{version}/source")
    Source source(@RequestAttribute(DocumentRequests.SOURCE_TENANT) String tenant,
            @PathVariable long id, @PathVariable int version,
            @RequestParam String unit, @RequestParam int start, @RequestParam int end,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        if (!UNITS.contains(unit) || start < 1 || end < start) {
            throw DocumentProblem.badRequest("原文位置无效");
        }
        Source result = jdbc.sql("""
                SELECT d.title, v.parsed_text, v.positions
                FROM doc_db.kb_document d JOIN doc_db.kb_document_version v ON v.document_id = d.id
                WHERE d.tenant_id = :tenant AND d.id = :id AND v.version = :version
                  AND v.status = 'READY' AND v.parsed_text IS NOT NULL AND v.positions IS NOT NULL
                """)
                .param("tenant", tenant).param("id", id).param("version", version)
                .query((row, index) -> {
                    String excerpt = excerpt(row.getString("parsed_text"), json.readTree(row.getString("positions")), unit, start, end);
                    return new Source(Long.toString(id), row.getString("title"), version, new Locator(unit, start, end), excerpt);
                })
                .optional().orElseThrow(DocumentProblem::notFound);
        LOG.info("source traceId={} document={} version={} unit={}", traceId, id, version, unit);
        return result;
    }

    private String excerpt(String text, JsonNode locations, String unit, int start, int end) {
        if (!unit.equals(locations.path("unit").stringValue(null))) {
            throw DocumentProblem.notFound();
        }
        int from = -1;
        int to = -1;
        for (JsonNode position : locations.path("positions")) {
            int number = position.path("number").asInt();
            if (number == start) {
                from = position.path("start").asInt(-1);
            }
            if (number == end) {
                to = position.path("end").asInt(-1);
                break;
            }
        }
        if (from < 0 || to <= from || to > text.length()) {
            throw DocumentProblem.notFound();
        }
        return text.substring(from, to);
    }

    private long documentId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // The internal request uses canonical positive document IDs.
        }
        throw DocumentProblem.badRequest("文档 ID 无效");
    }

    record DocumentIds(List<String> documentIds) {}

    record ActiveVersions(Map<String, Integer> versions) {}

    record Locator(String unit, int start, int end) {}

    record Source(String documentId, String title, int version, Locator locator, String text) {}
}
