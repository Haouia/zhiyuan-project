package com.ragknowledge.doc;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.databind.json.JsonMapper;

@Component
class DocumentPipeline {
    static final String PARSED_TOPIC = "rag.document.parsed.v1";
    static final String RESULT_TOPIC = "rag.document.result.v1";
    private static final Logger LOG = LoggerFactory.getLogger(DocumentPipeline.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final KafkaTemplate<String, String> kafka;
    private final DocumentParser parser = new DocumentParser();

    DocumentPipeline(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 500)
    void parseNext() {
        Work work = transactions.execute(status -> claim());
        if (work == null) {
            return;
        }
        DocumentParser.Parsed parsed;
        try {
            parsed = parser.parse(work.contentType(), work.bytes());
        } catch (DocumentParser.Failed error) {
            jdbc.sql("""
                    UPDATE doc_db.kb_document_version
                    SET status = 'FAILED', error_code = :code, parse_until = NULL, parse_claim = NULL
                    WHERE document_id = :id AND version = :version AND status = 'PARSING' AND parse_claim = :claim
                    """)
                    .param("code", error.getMessage()).param("id", work.id()).param("version", work.version())
                    .param("claim", work.claim()).update();
            return;
        }
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.sql("""
                    UPDATE doc_db.kb_document_version
                    SET status = 'INDEXING', parsed_text = :text, positions = :locations, parse_until = NULL, parse_claim = NULL
                    WHERE document_id = :id AND version = :version AND status = 'PARSING' AND parse_claim = :claim
                    """)
                    .param("text", parsed.text()).param("locations", json.writeValueAsString(parsed.locations()))
                    .param("id", work.id()).param("version", work.version()).param("claim", work.claim()).update();
            if (changed == 1) {
                Event event = new Event(UUID.randomUUID().toString(), "DocumentVersionParsed", work.tenant(),
                        Long.toString(work.id()), work.version(), null);
                jdbc.sql("""
                        INSERT INTO doc_db.event_outbox(event_id, event_type, tenant_id, document_id, version, payload)
                        VALUES (:event, :type, :tenant, :id, :version, :payload)
                        """)
                        .param("event", event.eventId()).param("type", event.type()).param("tenant", event.tenantId())
                        .param("id", work.id()).param("version", event.version()).param("payload", json.writeValueAsString(event))
                        .update();
            }
        });
    }

    private Work claim() {
        String claim = UUID.randomUUID().toString();
        Work work = jdbc.sql("""
                SELECT d.tenant_id, v.document_id, v.version, v.content_type, v.original_bytes, v.parse_attempts
                FROM doc_db.kb_document_version v JOIN doc_db.kb_document d ON d.id = v.document_id
                WHERE v.status = 'PENDING'
                   OR (v.status = 'PARSING' AND (v.parse_until IS NULL OR v.parse_until < CURRENT_TIMESTAMP(6)))
                ORDER BY v.created_at, v.document_id, v.version LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query((row, index) -> new Work(row.getString("tenant_id"), row.getLong("document_id"),
                        row.getInt("version"), row.getString("content_type"), row.getBytes("original_bytes"), claim,
                        row.getInt("parse_attempts")))
                .optional().orElse(null);
        if (work == null) {
            return null;
        }
        if (work.attempts() >= MAX_PARSE_ATTEMPTS) {
            // 只有租约过期的 PARSING 行会走到这里：已领取解析多次仍没有结果（意外异常或进程中断），不再无限重领
            jdbc.sql("""
                    UPDATE doc_db.kb_document_version
                    SET status = 'FAILED', error_code = 'PARSE_ABANDONED', parse_until = NULL, parse_claim = NULL
                    WHERE document_id = :id AND version = :version
                    """)
                    .param("id", work.id()).param("version", work.version()).update();
            LOG.warn("Document {} version {} abandoned after {} parse attempts", work.id(), work.version(), work.attempts());
            return null;
        }
        jdbc.sql("""
                UPDATE doc_db.kb_document_version
                SET status = 'PARSING', parse_claim = :claim, parse_until = TIMESTAMPADD(SECOND, 90, CURRENT_TIMESTAMP(6)),
                    parse_attempts = parse_attempts + 1
                WHERE document_id = :id AND version = :version
                """)
                .param("claim", work.claim()).param("id", work.id()).param("version", work.version()).update();
        return work;
    }

    @Scheduled(fixedDelay = 750)
    void publishParsed() {
        var pending = jdbc.sql("""
                SELECT event_id, payload, CONCAT(tenant_id, ':', document_id) AS event_key
                FROM doc_db.event_outbox WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT 20
                """).query((row, number) -> new Unsent(row.getString("event_id"), row.getString("event_key"),
                        row.getString("payload"))).list();
        // 批次已满时真实积压可能更多，指标必须报告总数，否则积压告警永远不会越过批大小
        PipelineMetrics.pending(pending.size() < 20 ? pending.size()
                : jdbc.sql("SELECT COUNT(*) FROM doc_db.event_outbox WHERE published_at IS NULL").query(Long.class).single());
        for (Unsent event : pending) {
            try {
                kafka.send(PARSED_TOPIC, event.key(), event.payload()).get(7, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                LOG.warn("Parsed event {} is pending Kafka delivery ({})", event.id(), error.getClass().getSimpleName());
                return;
            }
            jdbc.sql("UPDATE doc_db.event_outbox SET published_at = CURRENT_TIMESTAMP(6) WHERE event_id = :id AND published_at IS NULL")
                    .param("id", event.id()).update();
        }
    }

    @KafkaListener(topics = RESULT_TOPIC)
    void finish(ConsumerRecord<String, String> record) {
        Event event = json.readValue(record.value(), Event.class);
        long document = event.validDocument(record.key());
        boolean indexed = "DocumentVersionIndexed".equals(event.type());
        if (!indexed) {
            PipelineMetrics.indexFailure();
        }
        if (!indexed && !"DocumentVersionIndexFailed".equals(event.type())) {
            throw new IllegalArgumentException("Unexpected indexing outcome");
        }
        if (!indexed && (event.errorCode() == null || !event.errorCode().matches("[A-Z_]{1,64}"))) {
            throw new IllegalArgumentException("Invalid indexing failure code");
        }
        transactions.executeWithoutResult(status -> {
            var owner = jdbc.sql("SELECT id FROM doc_db.kb_document WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
                    .param("tenant", event.tenantId()).param("id", document).query(Long.class).optional();
            if (owner.isEmpty()) {
                return;
            }
            int changed = jdbc.sql("""
                    UPDATE doc_db.kb_document_version SET status = :status, error_code = :code
                    WHERE document_id = :id AND version = :version AND status = 'INDEXING'
                    """)
                    .param("status", indexed ? "READY" : "FAILED").param("code", indexed ? null : event.errorCode())
                    .param("id", document).param("version", event.version()).update();
            if (indexed && changed == 1) {
                jdbc.sql("""
                        UPDATE doc_db.kb_document SET active_version = :version
                        WHERE id = :id AND (active_version IS NULL OR active_version < :version)
                        """)
                        .param("version", event.version()).param("id", document).update();
            }
        });
    }

    record Event(String eventId, String type, String tenantId, String documentId, int version, String errorCode) {
        long validDocument(String key) {
            long id = Long.parseLong(documentId);
            if (id <= 0 || version <= 0 || !Long.toString(id).equals(documentId)
                    || tenantId == null || !tenantId.matches("[a-z0-9][a-z0-9-]{0,63}")
                    || !UUID.fromString(eventId).toString().equals(eventId)
                    || !Objects.equals(key, tenantId + ":" + documentId)) {
                throw new IllegalArgumentException("Invalid document event");
            }
            return id;
        }
    }

    private record Work(String tenant, long id, int version, String contentType, byte[] bytes, String claim, int attempts) {}

    private record Unsent(String id, String key, String payload) {}
}

@Configuration(proxyBeanMethods = false)
class DocumentTopics {
    @Bean
    KafkaAdmin.NewTopics newDocumentTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(DocumentPipeline.PARSED_TOPIC).partitions(1).replicas(1).build(),
                TopicBuilder.name(DocumentPipeline.RESULT_TOPIC).partitions(1).replicas(1).build(),
                TopicBuilder.name(DocumentPipeline.RESULT_TOPIC + ".DLT").partitions(1).replicas(1).build());
    }

    @Bean
    DefaultErrorHandler resultErrors(KafkaTemplate<String, String> kafka) {
        var deadLetter = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        deadLetter.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(deadLetter, new FixedBackOff(1000, 2));
    }
}
