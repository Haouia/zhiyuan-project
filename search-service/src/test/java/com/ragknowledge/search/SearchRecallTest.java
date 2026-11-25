package com.ragknowledge.search;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchRecallTest {
    @Test
    void bothOpenSearchLanesFilterByTenantAndWrongTenantHitsAreRejected() throws Exception {
        var json = new JsonMapper();
        var payload = new AtomicReference<String>();
        var candidateTenant = new AtomicReference<>("tenant-a");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chunks", exchange -> {
            try {
                String response;
                if (exchange.getRequestURI().getPath().endsWith("/_mapping")) {
                    response = """
                            {"chunks":{"mappings":{"_meta":{"embeddingModelVersion":"local-test-v1"},
                              "properties":{"embedding":{"dimension":2}}}}}
                            """;
                } else {
                    payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String lane = """
                            {"timed_out":false,"_shards":{"failed":0},"hits":{"hits":[{"_id":"tenant-a:1:1:0","_source":{
                              "tenantId":"%s","embeddingModelVersion":"local-test-v1","documentId":"1","title":"年假政策",
                              "version":1,"chunkIndex":0,"unit":"line","start":1,"end":1,"text":"年假 7 天"}}]}}
                            """.formatted(candidateTenant.get());
                    response = "{\"responses\":[" + lane + "," + lane + "]}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var embeddings = new Embeddings("", "", "local-test", "local-test-v1", 2);
            var search = new SearchIndex("http://127.0.0.1:" + server.getAddress().getPort(), "chunks", json, embeddings);
            var results = search.recall("tenant-a", "年假", new float[]{1, 0}, 20, List.of());
            assertEquals(1, results.keywords().size());
            assertEquals(1, results.semantic().size());
            String[] request = payload.get().split("\n");
            var keyword = json.readTree(request[1]).at("/query/bool/filter/bool/filter/0/term/tenantId");
            var semantic = json.readTree(request[3]).at("/query/knn/embedding/filter/bool/filter/0/term/tenantId");
            assertEquals("tenant-a", keyword.stringValue());
            assertEquals("tenant-a", semantic.stringValue());
            candidateTenant.set("tenant-b");
            assertThrows(IndexFailure.class, () -> search.recall("tenant-a", "年假", new float[]{1, 0}, 20, List.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ranksChunksByBothListsAndReturnsOneCopyOfEachChunk() {
        var first = new SearchIndex.Candidate("keyword", "1", "Title", 1, "Keyword text", null);
        var second = new SearchIndex.Candidate("semantic", "2", "Title", 1, "Semantic text", null);
        var both = new SearchIndex.Candidate("both", "3", "Title", 1, "Both lists", null);
        var result = SearchRetrieval.rank(new SearchIndex.Recall(List.of(first, both), List.of(second, both)));
        assertEquals(List.of(both, first, second), result);
    }
}
