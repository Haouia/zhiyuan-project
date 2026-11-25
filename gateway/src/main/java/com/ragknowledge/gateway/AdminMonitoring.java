package com.ragknowledge.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端系统监控：并行探活应用、平台与模型端点，并从各服务 Prometheus 指标聚合问答治理数据。
 *
 * <p>探活只走 Compose 内网地址，不依赖宿主机端口；问答指标取自 ai-service 的
 * {@code rag_questions_seconds}、{@code rag_question_results_total}、{@code rag_model_tokens_total}，
 * 流水线指标取自 doc/search 的 {@code rag_kafka_pending_events} 与 {@code rag_index_failures_total}。
 */
@RestController
class MonitoringController {
    private final Monitoring monitoring;

    MonitoringController(Monitoring monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping("/api/v1/monitoring")
    Mono<Map<String, Object>> snapshot() {
        return monitoring.snapshot();
    }
}

@Component
class Monitoring {
    private static final Logger LOG = LoggerFactory.getLogger(Monitoring.class);
    private static final Pattern METER = Pattern.compile(
            "^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{([^}]*)\\})?\\s+([-+]?[0-9]+(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|NaN|[-+]?Infinity)$");

    private final JsonMapper json;
    private final HttpClient client;
    private final String metricToken;
    private final List<Target> targets;
    private final Map<String, String> serviceUrls;

    Monitoring(JsonMapper json,
            @Value("${rag.metric-token:}") String metricToken,
            @Value("${rag.services.gateway:http://127.0.0.1:8080}") String gateway,
            @Value("${rag.services.doc:http://doc-service:8082}") String doc,
            @Value("${rag.services.search:http://search-service:8083}") String search,
            @Value("${rag.services.ai:http://ai-service:8081}") String ai,
            @Value("${rag.services.metric:http://metric-service:8090}") String metric,
            @Value("${rag.services.keycloak:http://keycloak:9000}") String keycloak,
            @Value("${rag.services.opensearch:http://opensearch:9200}") String opensearch,
            @Value("${rag.services.nacos:http://nacos:8848}") String nacos,
            @Value("${rag.services.model-primary:http://model-primary:8095}") String primary,
            @Value("${rag.services.model-backup:http://model-backup:8096}") String backup) {
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.metricToken = metricToken;
        this.serviceUrls = new LinkedHashMap<>();
        serviceUrls.put("gateway", trim(gateway));
        serviceUrls.put("doc-service", trim(doc));
        serviceUrls.put("search-service", trim(search));
        serviceUrls.put("ai-service", trim(ai));
        serviceUrls.put("metric-service", trim(metric));
        serviceUrls.put("keycloak", trim(keycloak));
        serviceUrls.put("opensearch", trim(opensearch));
        serviceUrls.put("nacos", trim(nacos));
        serviceUrls.put("model-primary", trim(primary));
        serviceUrls.put("model-backup", trim(backup));
        this.targets = List.of(
                new Target("gateway", "网关", "应用服务", "health", serviceUrls.get("gateway") + "/actuator/health", false),
                new Target("doc-service", "文档服务", "应用服务", "health", serviceUrls.get("doc-service") + "/actuator/health", false),
                new Target("search-service", "检索服务", "应用服务", "health", serviceUrls.get("search-service") + "/actuator/health", false),
                new Target("ai-service", "问答服务", "应用服务", "health", serviceUrls.get("ai-service") + "/actuator/health", false),
                new Target("metric-service", "评测服务", "平台服务", "metric", serviceUrls.get("metric-service") + "/internal/v1/health", true),
                new Target("keycloak", "身份服务", "平台服务", "keycloak", serviceUrls.get("keycloak") + "/health/ready", false),
                new Target("opensearch", "OpenSearch", "平台服务", "opensearch", serviceUrls.get("opensearch") + "/_cluster/health", false),
                new Target("nacos", "配置注册中心", "平台服务", "nacos", serviceUrls.get("nacos") + "/nacos/v1/console/health/readiness", false),
                new Target("model-primary", "主模型端点", "模型端点", "model", serviceUrls.get("model-primary") + "/health", false),
                new Target("model-backup", "备用模型端点", "模型端点", "model", serviceUrls.get("model-backup") + "/health", false));
    }

    Mono<Map<String, Object>> snapshot() {
        return Mono.fromCallable(this::snapshotSync).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> snapshotSync() {
        List<Map<String, Object>> services = probeAll();
        Map<String, String> prometheus = fetchPrometheus();
        Map<String, Object> metrics = aggregateMetrics(prometheus);
        for (Map<String, Object> service : services) {
            Object name = service.get("name");
            if (name instanceof String key && prometheus.containsKey(key)) {
                long uptime = (long) sumSeconds(parsePrometheus(prometheus.get(key)), "process_uptime_seconds");
                if (uptime > 0) {
                    service.put("uptimeSeconds", uptime);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", System.currentTimeMillis());
        result.put("services", services);
        result.put("metrics", metrics);
        return result;
    }

    private List<Map<String, Object>> probeAll() {
        List<Map<String, Object>> services = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> futures = targets.stream()
                    .map(target -> executor.submit(() -> probe(target)))
                    .toList();
            for (Future<Map<String, Object>> future : futures) {
                try {
                    services.add(future.get(8, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    LOG.warn("Monitoring probe failed: {}", failure.toString());
                }
            }
        }
        return services;
    }

    private Map<String, Object> probe(Target target) {
        long started = System.nanoTime();
        String status = "DOWN";
        String detail = "无法连接";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.url()))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            if (target.bearer()) {
                builder.header("Authorization", "Bearer " + metricToken);
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            Map<?, ?> payload = parseMap(body);
            switch (target.kind()) {
                case "nacos" -> {
                    status = response.statusCode() == 200 && body.strip().equalsIgnoreCase("OK") ? "UP" : "DEGRADED";
                    detail = "注册中心就绪";
                }
                case "opensearch" -> {
                    String cluster = string(payload.get("status"));
                    status = "red".equalsIgnoreCase(cluster) ? "DOWN"
                            : response.statusCode() == 200 ? "UP" : "DOWN";
                    detail = cluster + " · " + (long) number(payload.get("number_of_nodes")) + " 节点";
                }
                case "model" -> {
                    status = "UP".equalsIgnoreCase(string(payload.get("status"))) ? "UP" : "DEGRADED";
                    detail = "模型端点就绪";
                }
                case "keycloak" -> {
                    status = response.statusCode() == 200 && "UP".equalsIgnoreCase(string(payload.get("status")))
                            ? "UP" : "DOWN";
                    detail = "OIDC 就绪";
                }
                case "metric" -> {
                    status = response.statusCode() == 200 && "UP".equalsIgnoreCase(string(payload.get("status")))
                            ? "UP" : "DOWN";
                    detail = "评测服务就绪";
                }
                default -> {
                    String health = string(payload.get("status"));
                    status = "UP".equalsIgnoreCase(health) ? "UP"
                            : response.statusCode() >= 500 ? "DOWN" : "DEGRADED";
                    detail = detailForApp(target.name(), payload);
                }
            }
        } catch (Exception failure) {
            detail = failure.getClass().getSimpleName();
        }
        long latencyMs = Math.max(0, Math.round((System.nanoTime() - started) / 1_000_000.0));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", target.name());
        row.put("label", target.label());
        row.put("category", target.category());
        row.put("status", status);
        row.put("latencyMs", latencyMs);
        row.put("detail", detail);
        row.put("uptimeSeconds", 0L);
        return row;
    }

    private String detailForApp(String name, Map<?, ?> payload) {
        Map<?, ?> components = payload.get("components") instanceof Map<?, ?> map ? map : Map.of();
        String database = componentStatus(components, "db");
        String redis = componentStatus(components, "redis");
        String discovery = componentStatus(components, "discoveryComposite");
        List<String> parts = new ArrayList<>();
        if (database != null) {
            parts.add("数据库 " + database);
        }
        if (redis != null) {
            parts.add("Redis " + redis);
        }
        if (discovery != null) {
            parts.add("服务发现 " + discovery);
        }
        if (parts.isEmpty()) {
            return "gateway".equals(name) ? "网关运行正常" : "服务响应正常";
        }
        return String.join(" · ", parts);
    }

    private String componentStatus(Map<?, ?> components, String key) {
        if (components.get(key) instanceof Map<?, ?> component && component.get("status") instanceof String status) {
            return status;
        }
        return null;
    }

    private Map<String, String> fetchPrometheus() {
        Map<String, String> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : serviceUrls.entrySet()) {
            String key = entry.getKey();
            if (!List.of("gateway", "doc-service", "search-service", "ai-service").contains(key)) {
                continue;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(entry.getValue() + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    metrics.put(key, response.body());
                }
            } catch (Exception failure) {
                LOG.warn("Unable to read prometheus metrics from {}: {}", key, failure.toString());
            }
        }
        return metrics;
    }

    private Map<String, Object> aggregateMetrics(Map<String, String> prometheus) {
        List<Meter> ai = parsePrometheus(prometheus.getOrDefault("ai-service", ""));
        List<Meter> doc = parsePrometheus(prometheus.getOrDefault("doc-service", ""));
        List<Meter> search = parsePrometheus(prometheus.getOrDefault("search-service", ""));

        double totalQuestions = sum(ai, "rag_question_results_total", null, null);
        double answered = sum(ai, "rag_question_results_total", "outcome", "ANSWERED");
        double insufficient = sum(ai, "rag_question_results_total", "outcome", "INSUFFICIENT_EVIDENCE");
        double failures = Math.max(0, totalQuestions - answered - insufficient);
        double timerCount = sum(ai, "rag_questions_seconds_count", null, null);
        double timerSum = sum(ai, "rag_questions_seconds_sum", null, null);
        double latency = timerCount > 0 ? timerSum * 1000.0 / timerCount : 0.0;
        double successRate = totalQuestions > 0 ? answered * 100.0 / totalQuestions : 0.0;
        long inputTokens = (long) sum(ai, "rag_model_tokens_total", "direction", "in");
        long outputTokens = (long) sum(ai, "rag_model_tokens_total", "direction", "out");
        long failovers = (long) sum(ai, "rag_model_failover_total", null, null);
        long pending = (long) (sum(doc, "rag_kafka_pending_events", null, null)
                + sum(search, "rag_kafka_pending_events", null, null));
        long indexFailures = (long) (sum(doc, "rag_index_failures_total", null, null)
                + sum(search, "rag_index_failures_total", null, null));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("questionCount", (long) Math.max(0, totalQuestions));
        metrics.put("questionFailures", (long) Math.max(0, failures));
        metrics.put("questionSuccessRate", Math.round(successRate * 10.0) / 10.0);
        metrics.put("averageLatencyMs", Math.round(latency * 10.0) / 10.0);
        metrics.put("inputTokens", inputTokens);
        metrics.put("outputTokens", outputTokens);
        metrics.put("modelFailovers", failovers);
        metrics.put("kafkaPendingEvents", Math.max(0, pending));
        metrics.put("indexFailures", Math.max(0, indexFailures));
        return metrics;
    }

    private Map<?, ?> parseMap(String body) {
        if (body == null || body.isBlank() || !body.strip().startsWith("{")) {
            return Map.of();
        }
        try {
            Object parsed = json.readValue(body, Object.class);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (Exception failure) {
            return Map.of();
        }
    }

    private List<Meter> parsePrometheus(String text) {
        List<Meter> meters = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return meters;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = METER.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }
            try {
                meters.add(new Meter(matcher.group(1), parseTags(matcher.group(2)),
                        Double.parseDouble(matcher.group(3))));
            } catch (NumberFormatException ignored) {
                // Ignore values the local dashboard cannot interpret (NaN/Infinity).
            }
        }
        return meters;
    }

    private Map<String, String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                String value = pair.substring(separator + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                tags.put(pair.substring(0, separator).strip(), value);
            }
        }
        return tags;
    }

    private double sumSeconds(List<Meter> meters, String name) {
        return sum(meters, name, null, null);
    }

    private double sum(List<Meter> meters, String name, String tagKey, String tagValue) {
        double total = 0;
        for (Meter meter : meters) {
            if (!meter.name().equals(name)) {
                continue;
            }
            if (tagKey != null && !tagValue.equals(meter.tags().get(tagKey))) {
                continue;
            }
            total += meter.value();
        }
        return total;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String trim(String value) {
        String text = value == null ? "" : value.strip();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private record Target(String name, String label, String category, String kind, String url, boolean bearer) {}

    private record Meter(String name, Map<String, String> tags, double value) {}
}
