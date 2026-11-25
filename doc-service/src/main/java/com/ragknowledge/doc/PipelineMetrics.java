package com.ragknowledge.doc;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 文档流水线的 Kafka 待发事件积压与索引失败指标。 */
@Component
class PipelineMetrics {
    private static final AtomicLong PENDING = new AtomicLong();
    private static volatile MeterRegistry registry;

    PipelineMetrics(MeterRegistry meterRegistry) {
        registry = meterRegistry;
        Gauge.builder("rag.kafka.pending.events", PENDING, AtomicLong::get)
                .tag("service", "doc-service").description("未发送的本地事件数").register(meterRegistry);
    }

    static void pending(long count) {
        PENDING.set(count);
    }

    static void indexFailure() {
        count("rag.index.failures", "service", "doc-service");
    }

    static void count(String name, String... tags) {
        MeterRegistry target = registry;
        if (target == null) {
            return;
        }
        Counter.builder(name).tags(tags).register(target).increment();
    }
}
