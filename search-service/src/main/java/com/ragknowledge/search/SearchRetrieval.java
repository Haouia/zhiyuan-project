package com.ragknowledge.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
class SearchRetrieval {
    private static final Logger LOG = LoggerFactory.getLogger(SearchRetrieval.class);
    private static final List<Integer> WINDOWS = List.of(20, 60, 180);
    private static final int RANK_CONSTANT = 60;
    private final SearchIndex index;
    private final Embeddings embeddings;
    private final RestClient documents;
    private final byte[] authorization;

    SearchRetrieval(SearchIndex index, Embeddings embeddings, @LoadBalanced RestClient.Builder builder,
            @Value("${rag.search-doc-token}") String documentToken,
            @Value("${rag.ai-search-token}") String answerToken) {
        Assert.hasText(documentToken, "RAG_SEARCH_DOC_TOKEN is required");
        Assert.hasText(answerToken, "RAG_AI_SEARCH_TOKEN is required");
        Assert.isTrue(!documentToken.equals(answerToken), "Internal service credentials must be distinct");
        this.index = index;
        this.embeddings = embeddings;
        this.documents = builder.clone().baseUrl("http://doc-service").defaultHeaders(headers -> headers.setBearerAuth(documentToken)).build();
        this.authorization = ("Bearer " + answerToken).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/v1/retrieval")
    ResponseEntity<?> retrieve(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Trace-Id", required = false) String requestId,
            @RequestBody(required = false) Retrieval request) {
        String traceId = requestId != null && requestId.matches("[A-Za-z0-9_-]{1,64}") ? requestId : UUID.randomUUID().toString();
        if (token == null || !MessageDigest.isEqual(authorization, token.getBytes(StandardCharsets.UTF_8))) {
            return error(401, "UNAUTHORIZED", "内部服务凭证无效", traceId);
        }
        if (tenant == null || !tenant.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            return error(403, "FORBIDDEN", "内部租户上下文无效", traceId);
        }
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().length() > 1000 || request.topK() < 1 || request.topK() > 5) {
            return error(400, "BAD_REQUEST", "检索参数无效", traceId);
        }
        long started = System.nanoTime();
        try {
            Evidence evidence = new Evidence(find(tenant, request, traceId));
            LOG.info("retrieval traceId={} tenant={} candidates={} durationMs={}", traceId, tenant,
                    evidence.evidence().size(), (System.nanoTime() - started) / 1_000_000);
            return ResponseEntity.ok(evidence);
        } catch (IndexFailure | RestClientException failure) {
            LOG.warn("Retrieval {} was unavailable ({})", traceId, failure.getClass().getSimpleName());
            return error(503, "RETRIEVAL_UNAVAILABLE", "检索或版本核验暂不可用", traceId);
        }
    }

    private List<SearchIndex.Candidate> find(String tenant, Retrieval request, String traceId) {
        float[] vector = embeddings.embed(List.of(request.question())).getFirst();
        var notes = new LinkedHashMap<String, Integer>();
        List<SearchIndex.Candidate> active = List.of();
        for (int window : WINDOWS) {
            SearchIndex.Recall recall = index.recall(tenant, request.question(), vector, window, exclusions(notes));
            List<SearchIndex.Candidate> ranked = rank(recall);
            if (ranked.isEmpty()) {
                break;
            }
            List<String> ids = ranked.stream().map(SearchIndex.Candidate::documentId).distinct().toList();
            Map<String, Integer> versions = activeVersions(tenant, ids, traceId);
            active = ranked.stream().filter(hit -> Objects.equals(versions.get(hit.documentId()), hit.version()))
                    .limit(request.topK()).toList();
            if (active.size() >= request.topK() || recall.keywords().size() < window && recall.semantic().size() < window) {
                break;
            }
            for (SearchIndex.Candidate hit : ranked) {
                Integer version = versions.get(hit.documentId());
                if (!Objects.equals(version, hit.version())) {
                    notes.put(hit.documentId(), version);
                }
            }
        }
        return active;
    }

    private Map<String, Integer> activeVersions(String tenant, List<String> ids, String traceId) {
        ActiveVersions result;
        try {
            result = documents.post().uri("/internal/v1/documents/active-versions")
                    .header("X-Tenant-Id", tenant).header("X-Trace-Id", traceId).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("documentIds", ids)).retrieve().body(ActiveVersions.class);
        } catch (IllegalStateException unavailable) {
            // 负载均衡在依赖服务无可用实例时抛出 IllegalStateException，按版本核验不可用处理
            throw new IndexFailure("VERSION_CHECK_UNAVAILABLE");
        }
        if (result == null || result.versions() == null || result.versions().entrySet().stream()
                .anyMatch(entry -> !ids.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0)) {
            throw new IndexFailure("VERSION_CHECK_UNAVAILABLE");
        }
        return result.versions();
    }

    private List<Map<String, Object>> exclusions(Map<String, Integer> obsolete) {
        var result = new ArrayList<Map<String, Object>>();
        obsolete.forEach((document, version) -> {
            Map<String, Object> byId = SearchIndex.term("documentId", document);
            result.add(version == null ? byId : Map.of("bool",
                    Map.of("filter", byId, "must_not", SearchIndex.term("version", version))));
        });
        return result;
    }

    static List<SearchIndex.Candidate> rank(SearchIndex.Recall recall) {
        var matches = new HashMap<String, SearchIndex.Candidate>();
        var scores = new HashMap<String, Double>();
        Stream.of(recall.keywords(), recall.semantic()).forEach(lane -> {
            for (int offset = 0; offset < lane.size(); offset++) {
                SearchIndex.Candidate hit = lane.get(offset);
                matches.putIfAbsent(hit.chunkId(), hit);
                scores.merge(hit.chunkId(), 1.0 / (RANK_CONSTANT + offset + 1), Double::sum);
            }
        });
        return scores.keySet().stream()
                .sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(Comparator.naturalOrder()))
                .map(matches::get).toList();
    }

    private ResponseEntity<Map<String, String>> error(int status, String code, String message, String traceId) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "traceId", traceId));
    }

    record Retrieval(String question, int topK) {}

    record Evidence(List<SearchIndex.Candidate> evidence) {}

    record ActiveVersions(Map<String, Integer> versions) {}
}
