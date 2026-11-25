package com.ragknowledge.search;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingsTest {
    @Test
    void acceptsProviderMetadataAndValidatesTheReturnedIndices() throws Exception {
        var response = new AtomicReference<>("""
                {"object":"list","model":"test-model","usage":{"total_tokens":4},"data":[
                  {"object":"embedding","index":1,"embedding":[0,1]},
                  {"object":"embedding","index":0,"embedding":[1,0]}]}
                """);
        var authorization = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            try {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var service = new Embeddings("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings",
                    "test-credential", "test-model", "test-model-v1", 2);
            var text = List.of("年假", "考勤");
            List<float[]> vectors = service.embed(text);
            assertArrayEquals(new float[]{1, 0}, vectors.get(0));
            assertArrayEquals(new float[]{0, 1}, vectors.get(1));
            assertEquals("Bearer test-credential", authorization.get());
            assertEquals("年假", new JsonMapper().readTree(body.get()).path("input").get(0).stringValue());

            response.set("""
                    {"data":[{"index":0,"embedding":[1,0]},{"index":0,"embedding":[0,1]}]}
                    """);
            assertEquals("EMBEDDING_FAILED", assertThrows(IndexFailure.class, () -> service.embed(text)).getMessage());
        } finally {
            server.stop(0);
        }
    }
}
