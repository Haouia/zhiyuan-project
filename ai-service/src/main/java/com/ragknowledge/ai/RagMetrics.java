package com.ragknowledge.ai;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 问答与模型治理指标的静态入口。
 *
 * <p>Spring 容器启动时注入 MeterRegistry；离线单测不启动容器，此时埋点自动降级为 no-op，
 * 因此无需改造既有构造函数。指标名与 observability/grafana 面板中的 PromQL 对应。
 */
@Component
class RagMetrics {
    private static volatile MeterRegistry registry;

    RagMetrics(MeterRegistry meterRegistry) {
        registry = meterRegistry;
    }

    static void question(long durationMs, String outcome) {
        MeterRegistry target = registry;
        if (target == null) {
            return;
        }
        Timer.builder("rag.questions").tag("outcome", outcome).register(target).record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("rag.question.results").tag("outcome", outcome).register(target).increment();
    }

    static void failover(String from, String to) {
        count("rag.model.failover", "from_endpoint", from, "to_endpoint", to);
    }

    static void tokens(String model, String direction, long amount) {
        MeterRegistry target = registry;
        if (target == null || amount <= 0) {
            return;
        }
        Counter.builder("rag.model.tokens").tag("model", model).tag("direction", direction)
                .register(target).increment(amount);
    }

    static void count(String name, String... tags) {
        MeterRegistry target = registry;
        if (target == null) {
            return;
        }
        Counter.builder(name).tags(tags).register(target).increment();
    }
}
