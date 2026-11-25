package com.ragknowledge.search;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class Embeddings {
    private final URI endpoint;
    private final String model;
    private final String version;
    private final int dimensions;
    private final RestClient client;

    Embeddings(@Value("${rag.embedding-url}") String endpoint, @Value("${rag.embedding-api-key}") String key,
            @Value("${rag.embedding-model}") String model, @Value("${rag.embedding-version}") String version,
            @Value("${rag.embedding-dimensions}") int dimensions) {
        Assert.isTrue(dimensions > 0 && dimensions <= 4096, "Invalid embedding dimensions");
        Assert.isTrue(!version.isBlank() && version.length() <= 128, "Invalid embedding model version");
        this.endpoint = endpoint.isBlank() ? null : URI.create(endpoint);
        this.model = model;
        this.version = version;
        this.dimensions = dimensions;
        if (this.endpoint != null) {
            Assert.isTrue(this.endpoint.isAbsolute() && this.endpoint.getHost() != null
                    && List.of("https", "http").contains(this.endpoint.getScheme()), "Invalid embedding endpoint URI");
            Assert.isTrue(!model.isBlank() && !"local-test".equals(model) && !"local-test-v1".equals(version),
                    "Set model and version for the configured embedding endpoint");
        } else {
            Assert.isTrue("local-test".equals(model) && "local-test-v1".equals(version),
                    "An embedding endpoint is required for a configured model");
        }
        this.client = RestClient.builder().requestFactory(SearchPipelineConfiguration.http(Duration.ofSeconds(20)))
                .defaultHeaders(headers -> {
                    if (!key.isBlank()) {
                        headers.setBearerAuth(key);
                    }
                }).build();
    }

    List<float[]> embed(List<String> text) {
        if (endpoint == null) {
            return text.stream().map(this::localTestVector).toList();
        }
        Response response;
        try {
            response = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", text)).retrieve().body(Response.class);
        } catch (RestClientException error) {
            throw new IndexFailure("EMBEDDING_FAILED");
        }
        if (response == null || response.data() == null || response.data().size() != text.size()) {
            throw new IndexFailure("EMBEDDING_FAILED");
        }
        var vectors = new float[text.size()][];
        for (Item item : response.data()) {
            if (item == null || item.index() == null || item.index() < 0 || item.index() >= vectors.length
                    || vectors[item.index()] != null || !valid(item.embedding())) {
                throw new IndexFailure("EMBEDDING_FAILED");
            }
            vectors[item.index()] = item.embedding();
        }
        return Arrays.asList(vectors);
    }

    private float[] localTestVector(String text) {
        var vector = new float[dimensions];
        text.codePoints().forEach(value -> vector[Math.floorMod(Character.toLowerCase(value), dimensions)]++);
        double squaredLength = 0;
        for (float value : vector) {
            squaredLength += (double) value * value;
        }
        double length = Math.sqrt(squaredLength);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= (float) length;
        }
        return vector;
    }

    private boolean valid(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            return false;
        }
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return false;
            }
            nonzero |= value != 0;
        }
        return nonzero;
    }

    String version() {
        return version;
    }

    int dimensions() {
        return dimensions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Item> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(Integer index, float[] embedding) {}
}
