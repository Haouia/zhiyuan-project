package com.ragknowledge.search;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.json.JsonMapper;

@Component
class SearchPipeline {
    static final String PARSED_TOPIC = "rag.document.parsed.v1";
    static final String RESULT_TOPIC = "rag.document.result.v1";
    private static final Logger LOG = LoggerFactory.getLogger(SearchPipeline.class);
    private static final int BATCH_SIZE = 64;

    private final JdbcClient jdbc;
    private final JdbcTemplate rows;
    private final TransactionTemplate transactions;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Embeddings embeddings;
    private final SearchIndex index;
    private final RestClient documents;

    SearchPipeline(JdbcClient jdbc, JdbcTemplate rows, TransactionTemplate transactions, KafkaTemplate<String, String> kafka,
            JsonMapper json, Embeddings embeddings, SearchIndex index, @LoadBalanced RestClient.Builder documents,
            @Value("${rag.search-doc-token}") String token) {
        Assert.hasText(token, "RAG_SEARCH_DOC_TOKEN is required");
        this.jdbc = jdbc;
        this.rows = rows;
        this.transactions = transactions;
        this.kafka = kafka;
        this.json = json;
        this.embeddings = embeddings;
        this.index = index;
        this.documents = documents.clone().baseUrl("http://doc-service")
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    @KafkaListener(topics = PARSED_TOPIC)
    void indexVersion(ConsumerRecord<String, String> record) {
        Event event = event(record);
        if (completed(event)) {
            return;
        }
        Parsed document = fetch(event);
        List<Chunk> chunks = split(document);
        index.ensureMapping();
        for (int offset = 0; offset < chunks.size(); offset += BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(offset, Math.min(offset + BATCH_SIZE, chunks.size()));
            index.write(event, document.title(), batch, embeddings.embed(batch.stream().map(Chunk::text).toList()));
        }
        index.requireSearchable(event, chunks.size());

        transactions.executeWithoutResult(status -> {
            rows.batchUpdate("""
                    INSERT INTO search_db.doc_chunk(tenant_id, document_id, version, chunk_index, chunk_text,
                        locator_unit, start_position, end_position, embedding_model_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS incoming
                    ON DUPLICATE KEY UPDATE chunk_text = incoming.chunk_text, locator_unit = incoming.locator_unit,
                       start_position = incoming.start_position, end_position = incoming.end_position,
                       embedding_model_version = incoming.embedding_model_version
                    """, chunks, BATCH_SIZE, (statement, chunk) -> {
                        statement.setString(1, event.tenantId());
                        statement.setLong(2, Long.parseLong(event.documentId()));
                        statement.setInt(3, event.version());
                        statement.setInt(4, chunk.number());
                        statement.setString(5, chunk.text());
                        statement.setString(6, chunk.unit());
                        statement.setInt(7, chunk.start());
                        statement.setInt(8, chunk.end());
                        statement.setString(9, embeddings.version());
                    });
            outcome(event, "DocumentVersionIndexed", null);
        });
    }

    private Parsed fetch(Event event) {
        Parsed document;
        try {
            document = documents.get().uri("/internal/v1/documents/{id}/versions/{version}/parsed",
                            event.documentId(), event.version())
                    .header("X-Tenant-Id", event.tenantId()).retrieve().body(Parsed.class);
        } catch (RestClientException error) {
            throw new IndexFailure("PARSED_DOCUMENT_UNAVAILABLE");
        }
        if (document == null || !event.documentId().equals(document.documentId()) || event.version() != document.version()) {
            throw new IndexFailure("PARSED_DOCUMENT_UNAVAILABLE");
        }
        return document;
    }

    private List<Chunk> split(Parsed document) {
        List<Position> positions = document.locations().positions();
        String unit = document.locations().unit();
        String text = document.text();
        if (positions == null || positions.isEmpty() || !Set.of("line", "page", "paragraph").contains(unit)) {
            throw new IndexFailure("INVALID_PARSED_TEXT");
        }
        int previousEnd = -1;
        int previousNumber = 0;
        for (Position position : positions) {
            if (position.number() <= previousNumber || position.start() <= previousEnd
                    || position.end() <= position.start() || position.end() > text.length()) {
                throw new IndexFailure("INVALID_PARSED_TEXT");
            }
            previousEnd = position.end();
            previousNumber = position.number();
        }

        var chunks = new ArrayList<Chunk>();
        int positionIndex = 0;
        int start = positions.getFirst().start();
        while (positionIndex < positions.size()) {
            int from = positions.get(positionIndex).number();
            int limit = Math.min(text.length(), start + 800);
            if (limit < text.length() && Character.isLowSurrogate(text.charAt(limit))) {
                limit--;
            }
            while (positionIndex + 1 < positions.size() && positions.get(positionIndex + 1).start() < limit) {
                positionIndex++;
            }
            Position last = positions.get(positionIndex);
            int end = Math.min(limit, last.end());
            String chunk = text.substring(start, end);
            if (!chunk.isBlank()) {
                chunks.add(new Chunk(chunks.size(), chunk, unit, from, last.number()));
            }
            if (end == last.end()) {
                positionIndex++;
                if (positionIndex < positions.size()) {
                    start = positions.get(positionIndex).start();
                }
            } else {
                start = end;
            }
        }
        if (chunks.isEmpty()) {
            throw new IndexFailure("INVALID_PARSED_TEXT");
        }
        return chunks;
    }

    private boolean completed(Event event) {
        return jdbc.sql("""
                SELECT event_id FROM search_db.event_outbox
                WHERE tenant_id = :tenant AND document_id = :id AND version = :version
                """)
                .param("tenant", event.tenantId()).param("id", Long.parseLong(event.documentId())).param("version", event.version())
                .query(String.class).optional().isPresent();
    }

    private void outcome(Event input, String type, String code) {
        Event result = new Event(UUID.randomUUID().toString(), type, input.tenantId(), input.documentId(), input.version(), code);
        jdbc.sql("""
                INSERT INTO search_db.event_outbox(event_id, event_type, tenant_id, document_id, version, payload)
                VALUES (:event, :type, :tenant, :id, :version, :payload)
                ON DUPLICATE KEY UPDATE event_id = event_id
                """)
                .param("event", result.eventId()).param("type", type).param("tenant", result.tenantId())
                .param("id", Long.parseLong(result.documentId())).param("version", result.version())
                .param("payload", json.writeValueAsString(result)).update();
    }

    void exhausted(ConsumerRecord<?, ?> record, Exception failure) {
        Event event;
        try {
            event = event(record);
        } catch (RuntimeException malformed) {
            return;
        }
        String code = "INDEXING_FAILED";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IndexFailure problem) {
                code = problem.getMessage();
                break;
            }
        }
        outcome(event, "DocumentVersionIndexFailed", code);
        PipelineMetrics.indexFailure();
    }

    private Event event(ConsumerRecord<?, ?> record) {
        Event event = json.readValue((String) record.value(), Event.class);
        long id = Long.parseLong(event.documentId());
        if (!"DocumentVersionParsed".equals(event.type()) || id <= 0 || !Long.toString(id).equals(event.documentId())
                || event.version() <= 0 || event.tenantId() == null || !event.tenantId().matches("[a-z0-9][a-z0-9-]{0,63}")
                || !UUID.fromString(event.eventId()).toString().equals(event.eventId())
                || !Objects.equals(record.key(), event.tenantId() + ":" + event.documentId())) {
            throw new IllegalArgumentException("Invalid parsed document event");
        }
        return event;
    }

    @Scheduled(fixedDelay = 750)
    void publishOutcomes() {
        var pending = jdbc.sql("""
                SELECT event_id, payload, CONCAT(tenant_id, ':', document_id) AS event_key
                FROM search_db.event_outbox WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT 20
                """).query((row, number) -> new Unsent(row.getString("event_id"), row.getString("event_key"),
                        row.getString("payload"))).list();
        // 批次已满时真实积压可能更多，指标必须报告总数，否则积压告警永远不会越过批大小
        PipelineMetrics.pending(pending.size() < 20 ? pending.size()
                : jdbc.sql("SELECT COUNT(*) FROM search_db.event_outbox WHERE published_at IS NULL").query(Long.class).single());
        for (Unsent event : pending) {
            try {
                kafka.send(RESULT_TOPIC, event.key(), event.payload()).get(7, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                LOG.warn("Index event {} is pending Kafka delivery ({})", event.id(), error.getClass().getSimpleName());
                return;
            }
            jdbc.sql("UPDATE search_db.event_outbox SET published_at = CURRENT_TIMESTAMP(6) WHERE event_id = :id AND published_at IS NULL")
                    .param("id", event.id()).update();
        }
    }

    record Event(String eventId, String type, String tenantId, String documentId, int version, String errorCode) {}

    record Chunk(int number, String text, String unit, int start, int end) {}

    record Parsed(String documentId, String title, int version, String text, Locations locations) {}

    record Locations(String unit, List<Position> positions) {}

    record Position(int number, int start, int end) {}

    private record Unsent(String id, String key, String payload) {}
}

class IndexFailure extends RuntimeException {
    IndexFailure(String code) {
        super(code);
    }
}

@Configuration(proxyBeanMethods = false)
class SearchPipelineConfiguration {
    @Bean
    @LoadBalanced
    RestClient.Builder documentClient() {
        return RestClient.builder().requestFactory(http(Duration.ofSeconds(10)));
    }

    static JdkClientHttpRequestFactory http(Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic parsedDeadLetterTopic() {
        return TopicBuilder.name(SearchPipeline.PARSED_TOPIC + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler indexErrors(KafkaTemplate<String, String> kafka, SearchPipeline search) {
        var deadLetter = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        deadLetter.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler((record, failure) -> {
            search.exhausted(record, failure);
            deadLetter.accept(record, failure);
        }, new FixedBackOff(1000, 2));
    }
}
