package com.ragknowledge.search;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class SearchIndex {
    private static final Set<String> UNITS = Set.of("line", "page", "paragraph");
    private final RestClient client;
    private final String name;
    private final JsonMapper json;
    private final Embeddings embeddings;
    private boolean mapped;

    SearchIndex(@Value("${rag.opensearch-url}") String url, @Value("${rag.opensearch-index}") String name,
            JsonMapper json, Embeddings embeddings) {
        Assert.isTrue(name.matches("[a-z0-9][a-z0-9_-]{0,127}"), "Invalid OpenSearch index name");
        this.client = RestClient.builder().baseUrl(url)
                .requestFactory(SearchPipelineConfiguration.http(Duration.ofSeconds(10))).build();
        this.name = name;
        this.json = json;
        this.embeddings = embeddings;
    }

    synchronized void ensureMapping() {
        if (mapped) {
            return;
        }
        try {
            JsonNode mapping;
            try {
                mapping = mapping();
            } catch (HttpClientErrorException.NotFound missing) {
                try {
                    client.put().uri("/{index}", name).contentType(MediaType.APPLICATION_JSON)
                            .body(definition()).retrieve().toBodilessEntity();
                } catch (HttpClientErrorException.BadRequest race) {
                    if (!race.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                        throw race;
                    }
                }
                mapping = mapping();
            }
            if (mapping == null || !embeddings.version().equals(mapping.path("_meta").path("embeddingModelVersion").stringValue(null))
                    || mapping.path("properties").path("embedding").path("dimension").asInt() != embeddings.dimensions()) {
                throw new IndexFailure("INDEX_MAPPING_MISMATCH");
            }
            mapped = true;
        } catch (RestClientException error) {
            throw new IndexFailure("INDEX_WRITE_FAILED");
        }
    }

    private JsonNode mapping() {
        JsonNode response = client.get().uri("/{index}/_mapping", name).retrieve().body(JsonNode.class);
        return response == null ? null : response.path(name).path("mappings");
    }

    private Map<String, Object> definition() {
        Map<String, String> keyword = Map.of("type", "keyword");
        Map<String, String> integer = Map.of("type", "integer");
        return Map.of("settings", Map.of("index.knn", true, "number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Map.of("_meta", Map.of("embeddingModelVersion", embeddings.version()),
                        "properties", Map.ofEntries(
                                Map.entry("tenantId", keyword), Map.entry("documentId", keyword),
                                Map.entry("version", integer), Map.entry("chunkIndex", integer),
                                Map.entry("title", Map.of("type", "text", "analyzer", "cjk")),
                                Map.entry("text", Map.of("type", "text", "analyzer", "cjk")),
                                Map.entry("unit", keyword), Map.entry("start", integer), Map.entry("end", integer),
                                Map.entry("embeddingModelVersion", keyword),
                                Map.entry("embedding", Map.of("type", "knn_vector", "dimension", embeddings.dimensions(),
                                        "method", Map.of("name", "hnsw", "engine", "lucene", "space_type", "cosinesimil"))))));
    }

    void write(SearchPipeline.Event event, String title, List<SearchPipeline.Chunk> chunks, List<float[]> vectors) {
        var body = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            SearchPipeline.Chunk chunk = chunks.get(index);
            String id = event.tenantId() + ":" + event.documentId() + ":" + event.version() + ":" + chunk.number();
            body.append(json.writeValueAsString(Map.of("index", Map.of("_id", id)))).append('\n');
            body.append(json.writeValueAsString(new IndexedChunk(event.tenantId(), event.documentId(), event.version(),
                    chunk.number(), title, chunk.text(), chunk.unit(), chunk.start(), chunk.end(), embeddings.version(),
                    vectors.get(index)))).append('\n');
        }
        JsonNode response;
        try {
            response = client.post().uri("/{index}/_bulk?refresh=wait_for", name)
                    .contentType(MediaType.parseMediaType("application/x-ndjson"))
                    .body(body.toString().getBytes(StandardCharsets.UTF_8)).retrieve().body(JsonNode.class);
        } catch (RestClientException error) {
            throw new IndexFailure("INDEX_WRITE_FAILED");
        }
        if (response == null || !response.path("errors").isBoolean() || response.path("errors").asBoolean()
                || response.path("items").size() != chunks.size()) {
            throw new IndexFailure("INDEX_WRITE_FAILED");
        }
    }

    void requireSearchable(SearchPipeline.Event event, int expected) {
        var query = Map.of("query", Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("tenantId", event.tenantId())),
                Map.of("term", Map.of("documentId", event.documentId())),
                Map.of("term", Map.of("version", event.version()))))));
        JsonNode count;
        try {
            count = client.post().uri("/{index}/_count", name).contentType(MediaType.APPLICATION_JSON)
                    .body(query).retrieve().body(JsonNode.class);
        } catch (RestClientException error) {
            throw new IndexFailure("INDEX_WRITE_FAILED");
        }
        if (count == null || count.path("_shards").path("failed").asInt(-1) != 0 || count.path("count").asInt(-1) != expected) {
            throw new IndexFailure("INDEX_INCOMPLETE");
        }
    }

    Recall recall(String tenant, String question, float[] vector, int limit, List<Map<String, Object>> exclusions) {
        ensureMapping();
        var eligible = Map.of("bool", Map.of(
                "filter", List.of(term("tenantId", tenant), term("embeddingModelVersion", embeddings.version())),
                "must_not", exclusions));
        var keyword = Map.of("bool", Map.of("filter", eligible, "must",
                Map.of("multi_match", Map.of("query", question, "fields", List.of("text", "title^1.3")))));
        var semantic = Map.of("knn", Map.of("embedding", Map.of("vector", vector, "k", limit, "filter", eligible)));
        String body = "{}\n" + json.writeValueAsString(searchBody(keyword, limit)) + "\n"
                + "{}\n" + json.writeValueAsString(searchBody(semantic, limit)) + "\n";
        JsonNode responses;
        try {
            JsonNode result = client.post().uri("/{index}/_msearch", name)
                    .contentType(MediaType.parseMediaType("application/x-ndjson"))
                    .body(body.getBytes(StandardCharsets.UTF_8)).retrieve().body(JsonNode.class);
            responses = result == null ? null : result.path("responses");
        } catch (RestClientException error) {
            throw new IndexFailure("RETRIEVAL_UNAVAILABLE");
        }
        if (responses == null || !responses.isArray() || responses.size() != 2) {
            throw new IndexFailure("RETRIEVAL_UNAVAILABLE");
        }
        return new Recall(candidates(responses.get(0), tenant), candidates(responses.get(1), tenant));
    }

    private Map<String, Object> searchBody(Map<String, ?> query, int limit) {
        return Map.of("size", limit, "query", query, "_source", Map.of("excludes", List.of("embedding")));
    }

    private List<Candidate> candidates(JsonNode response, String tenant) {
        JsonNode hits = response.path("hits").path("hits");
        if (response.has("error") || response.path("timed_out").asBoolean(true)
                || response.path("_shards").path("failed").asInt(-1) != 0 || !hits.isArray()) {
            throw new IndexFailure("RETRIEVAL_UNAVAILABLE");
        }
        var candidates = new ArrayList<Candidate>(hits.size());
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            String id = hit.path("_id").stringValue(null);
            String document = source.path("documentId").stringValue(null);
            String title = source.path("title").stringValue(null);
            int version = source.path("version").asInt(-1);
            int chunk = source.path("chunkIndex").asInt(-1);
            String unit = source.path("unit").stringValue(null);
            int start = source.path("start").asInt(-1);
            int end = source.path("end").asInt(-1);
            String text = source.path("text").stringValue(null);
            if (!tenant.equals(source.path("tenantId").stringValue(null))
                    || !embeddings.version().equals(source.path("embeddingModelVersion").stringValue(null))
                    || document == null || !document.matches("[1-9][0-9]{0,18}") || title == null
                    || version < 1 || chunk < 0 || unit == null || !UNITS.contains(unit) || start < 1 || end < start
                    || text == null || text.isBlank() || text.length() > 800
                    || !(tenant + ":" + document + ":" + version + ":" + chunk).equals(id)) {
                throw new IndexFailure("RETRIEVAL_UNAVAILABLE");
            }
            candidates.add(new Candidate(id, document, title, version, text, new Locator(unit, start, end)));
        }
        return candidates;
    }

    static Map<String, Object> term(String field, Object value) {
        return Map.of("term", Map.of(field, value));
    }

    record IndexedChunk(String tenantId, String documentId, int version, int chunkIndex, String title, String text,
            String unit, int start, int end, String embeddingModelVersion, float[] embedding) {}

    record Locator(String unit, int start, int end) {}

    record Candidate(String chunkId, String documentId, String title, int version, String text, Locator locator) {}

    record Recall(List<Candidate> keywords, List<Candidate> semantic) {}
}
