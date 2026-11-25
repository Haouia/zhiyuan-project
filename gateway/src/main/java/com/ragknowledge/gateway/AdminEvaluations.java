package com.ragknowledge.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * RAGAS 离线评测的只读报告接口与在线触发入口。
 *
 * <p>报告归档目录由 Compose 绑定挂载到容器内 {@code /app/eval}，与离线作业
 * {@code scripts/EvalJob.java} 使用同一套报告格式。列表与详情中的质量分数统一按
 * 可答题（金标样本含 reference 的 30 题）计算，避免资料不足题拉低均值。
 */
@RestController
class EvaluationController {
    private final Evaluations evaluations;

    EvaluationController(Evaluations evaluations) {
        this.evaluations = evaluations;
    }

    @GetMapping("/api/v1/evals")
    Mono<Map<String, Object>> list() {
        return evaluations.listReports();
    }

    @GetMapping("/api/v1/evals/{reportId}")
    Mono<Map<String, Object>> detail(@PathVariable String reportId) {
        return evaluations.report(reportId);
    }

    @PostMapping("/api/v1/evals")
    Mono<ResponseEntity<Map<String, Object>>> run(ServerWebExchange exchange) {
        return evaluations.run(exchange.getRequest().getId());
    }
}

@org.springframework.stereotype.Component
class Evaluations {
    private static final Logger LOG = LoggerFactory.getLogger(Evaluations.class);
    private static final Pattern REPORT_ID = Pattern.compile("report-[0-9]{1,20}");
    private static final List<String> METRICS = List.of("faithfulness", "context_recall");

    private final JsonMapper json;
    private final Path reportsDir;
    private final Path datasetFile;
    private final String metricUrl;
    private final String metricToken;
    private final HttpClient client;
    private final AtomicBoolean running = new AtomicBoolean();

    Evaluations(JsonMapper json,
            @Value("${rag.eval-dir:/app/eval}") String evalDir,
            @Value("${rag.metric-url:http://metric-service:8090}") String metricUrl,
            @Value("${rag.metric-token:}") String metricToken) {
        this.json = json;
        Path root = Path.of(evalDir);
        this.reportsDir = root.resolve("reports");
        this.datasetFile = root.resolve("golden.json");
        this.metricUrl = stripTrailingSlash(metricUrl);
        this.metricToken = metricToken;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    Mono<Map<String, Object>> listReports() {
        return Mono.fromCallable(this::listSync).subscribeOn(Schedulers.boundedElastic());
    }

    Mono<Map<String, Object>> report(String reportId) {
        return Mono.fromCallable(() -> detailSync(reportId)).subscribeOn(Schedulers.boundedElastic());
    }

    Mono<ResponseEntity<Map<String, Object>>> run(String traceId) {
        return Mono.fromCallable(() -> runSync(traceId)).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> listSync() {
        List<Map<String, Object>> items = new ArrayList<>();
        if (Files.isDirectory(reportsDir)) {
            try (var files = Files.list(reportsDir)) {
                for (Path file : files.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                    Map<String, Object> item = summary(file);
                    if (item != null) {
                        items.add(item);
                    }
                }
            } catch (IOException failure) {
                // Windows 挂载卷上并发写入报告文件时 Files.list 偶发失败，重试一次再报错。
                LOG.warn("Unable to list evaluation reports in {}: {} (will retry once)", reportsDir, failure.toString());
                try {
                    Thread.sleep(120);
                    try (var files = Files.list(reportsDir)) {
                        for (Path file : files.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                            Map<String, Object> item = summary(file);
                            if (item != null) {
                                items.add(item);
                            }
                        }
                    }
                } catch (Exception retryFailure) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评测报告目录不可读");
                }
            }
        }
        items.sort((left, right) -> Long.compare(number(right.get("generatedAt")), number(left.get("generatedAt"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        return result;
    }

    private Map<String, Object> detailSync(String reportId) {
        if (reportId == null || !REPORT_ID.matcher(reportId).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path file = reportsDir.resolve(reportId + ".json").normalize();
        if (!reportsDir.normalize().equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<?, ?> report = readReport(file);
        List<?> samples = list(report.get("samples"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : samples) {
            if (!(item instanceof Map<?, ?> sample)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userInput", sample.get("user_input"));
            row.put("faithfulness", sample.get("faithfulness"));
            row.put("contextRecall", sample.get("context_recall"));
            row.put("error", sample.get("error"));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("dataset", text(report.get("dataset"), "unknown"));
        result.put("status", text(report.get("status"), "FAILED"));
        result.put("generatedAt", number(report.get("generatedAt")));
        result.put("redacted", Boolean.TRUE.equals(report.get("redacted")));
        result.put("reason", report.get("reason"));
        result.put("sampleCount", rows.size());
        result.put("averages", gateAverages(report));
        result.put("samples", rows);
        return result;
    }

    private Map<String, Object> summary(Path file) {
        String name = file.getFileName().toString();
        String reportId = name.substring(0, name.length() - ".json".length());
        if (!REPORT_ID.matcher(reportId).matches()) {
            return null;
        }
        try {
            Map<?, ?> report = readReport(file);
            List<?> samples = list(report.get("samples"));
            Map<String, Object> gate = gateAverages(report);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportId", reportId);
            item.put("dataset", text(report.get("dataset"), "unknown"));
            item.put("status", text(report.get("status"), "FAILED"));
            item.put("generatedAt", number(report.get("generatedAt")));
            item.put("sampleCount", samples.size());
            item.put("faithfulness", gate.get("faithfulness"));
            item.put("contextRecall", gate.get("contextRecall"));
            item.put("reason", report.get("reason"));
            return item;
        } catch (Exception failure) {
            LOG.warn("Skipping malformed evaluation report {}: {}", name, failure.toString());
            return null;
        }
    }

    /**
     * 可答题均值：金标样本中带 reference 的题目才计入质量门槛，资料不足题单独验收拒答。
     * 旧报告没有记录 reference 标记，按样本顺序与当前金标集对齐；长度不一致时退回报告内均值。
     */
    private Map<String, Object> gateAverages(Map<?, ?> report) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<?> samples = list(report.get("samples"));
        List<Integer> answerable = answerableIndexes(samples.size());
        if (answerable.isEmpty()) {
            Map<?, ?> fallback = report.get("averages") instanceof Map<?, ?> averages ? averages : Map.of();
            result.put("faithfulness", fallback.get("faithfulness"));
            result.put("contextRecall", fallback.get("context_recall"));
            return result;
        }
        for (String metric : METRICS) {
            double sum = 0;
            int count = 0;
            for (Integer index : answerable) {
                if (index >= samples.size() || !(samples.get(index) instanceof Map<?, ?> sample)) {
                    continue;
                }
                Object value = sample.get(metric);
                if (value instanceof Number number) {
                    sum += number.doubleValue();
                    count++;
                }
            }
            result.put(metric.equals("faithfulness") ? "faithfulness" : "contextRecall",
                    count == 0 ? null : sum / count);
        }
        return result;
    }

    private List<Integer> answerableIndexes(int reportSamples) {
        if (!Files.isRegularFile(datasetFile)) {
            return List.of();
        }
        try {
            Object parsed = json.readValue(Files.readString(datasetFile, StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof Map<?, ?> golden) || !(golden.get("samples") instanceof List<?> samples)
                    || samples.size() != reportSamples) {
                return List.of();
            }
            List<Integer> indexes = new ArrayList<>();
            for (int index = 0; index < samples.size(); index++) {
                if (samples.get(index) instanceof Map<?, ?> sample && sample.containsKey("reference")) {
                    indexes.add(index);
                }
            }
            return indexes;
        } catch (Exception failure) {
            LOG.warn("Unable to read golden dataset {}: {}", datasetFile, failure.toString());
            return List.of();
        }
    }

    private ResponseEntity<Map<String, Object>> runSync(String traceId) {
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "EVALUATION_IN_PROGRESS",
                    "message", "已有评测任务正在运行，请稍后重试",
                    "traceId", traceId));
        }
        try {
            long generatedAt = System.currentTimeMillis();
            Map<String, Object> report = evaluate(generatedAt);
            String reportId = "report-" + generatedAt;
            Path target = reportsDir.resolve(reportId + ".json");
            try {
                Files.createDirectories(reportsDir);
                // 先写临时文件再原子移动：避免并发 GET /api/v1/evals 读到半写文件。
                Path temp = reportsDir.resolve(reportId + ".json.tmp");
                Files.writeString(temp, json.writeValueAsString(report), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) {
                LOG.error("Unable to archive evaluation report {}: {}", target, failure.toString());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评测报告归档失败");
            }
            Map<String, Object> gate = gateAverages(report);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("reportId", reportId);
            summary.put("dataset", text(report.get("dataset"), "golden-v1"));
            summary.put("status", report.get("status"));
            summary.put("generatedAt", generatedAt);
            summary.put("sampleCount", list(report.get("samples")).size());
            summary.put("faithfulness", gate.get("faithfulness"));
            summary.put("contextRecall", gate.get("contextRecall"));
            summary.put("averages", gate);
            summary.put("reason", report.get("reason"));
            return ResponseEntity.ok(summary);
        } finally {
            running.set(false);
        }
    }

    private Map<String, Object> evaluate(long generatedAt) {
        Map<?, ?> golden = readGolden();
        List<?> samples = list(golden.get("samples"));
        List<Object> rows = new ArrayList<>();
        String reason = null;
        int index = 0;
        for (; index < samples.size(); index++) {
            if (!(samples.get(index) instanceof Map<?, ?> sample)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("user_input", null);
                entry.put("faithfulness", null);
                entry.put("context_recall", null);
                entry.put("error", "金标样本格式无效");
                rows.add(entry);
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user_input", redact(sample.get("user_input")));
            try {
                Map<String, Object> scores = score(sample);
                entry.put("faithfulness", scores.get("faithfulness"));
                entry.put("context_recall", scores.get("context_recall"));
                entry.put("error", null);
            } catch (Exception failure) {
                reason = failure.toString();
                entry.put("faithfulness", null);
                entry.put("context_recall", null);
                entry.put("error", reason);
                rows.add(entry);
                index++;
                break;
            }
            rows.add(entry);
        }
        for (; index < samples.size(); index++) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            Object sample = samples.get(index);
            skipped.put("user_input", sample instanceof Map<?, ?> map ? redact(map.get("user_input")) : null);
            skipped.put("faithfulness", null);
            skipped.put("context_recall", null);
            skipped.put("error", "评测中断，未执行");
            rows.add(skipped);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", text(golden.get("name"), "golden-v1"));
        report.put("status", reason == null ? "OK" : "FAILED");
        report.put("redacted", true);
        report.put("reason", reason);
        report.put("generatedAt", generatedAt);
        report.put("samples", rows);
        report.put("averages", overallAverages(rows));
        return report;
    }

    private Map<String, Object> score(Map<?, ?> sample) throws Exception {
        if (metricToken == null || metricToken.isBlank()) {
            throw new IllegalStateException("RAG_METRIC_TOKEN is not configured");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_input", sample.get("user_input"));
        payload.put("response", sample.get("response"));
        payload.put("retrieved_contexts", sample.get("retrieved_contexts"));
        if (sample.containsKey("reference")) {
            payload.put("reference", sample.get("reference"));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(metricUrl + "/internal/v1/evaluations"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + metricToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("metric-service returned HTTP " + response.statusCode());
        }
        Object parsed = json.readValue(response.body(), Object.class);
        if (!(parsed instanceof Map<?, ?> scores)) {
            throw new IllegalStateException("metric-service returned an invalid score payload");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("faithfulness", scores.get("faithfulness"));
        result.put("context_recall", scores.get("context_recall"));
        return result;
    }

    private Map<String, Object> overallAverages(List<Object> rows) {
        Map<String, Object> averages = new LinkedHashMap<>();
        for (String metric : METRICS) {
            double sum = 0;
            int count = 0;
            for (Object item : rows) {
                if (item instanceof Map<?, ?> sample && sample.get(metric) instanceof Number number) {
                    sum += number.doubleValue();
                    count++;
                }
            }
            averages.put(metric, count == 0 ? null : sum / count);
        }
        return averages;
    }

    private Map<?, ?> readGolden() {
        if (!Files.isRegularFile(datasetFile)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评测数据集缺失");
        }
        try {
            Object parsed = json.readValue(Files.readString(datasetFile, StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof Map<?, ?> golden) || !(golden.get("samples") instanceof List<?> samples)
                    || samples.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评测数据集格式无效");
            }
            return golden;
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评测数据集不可读");
        }
    }

    private Map<?, ?> readReport(Path file) {
        try {
            Object parsed = json.readValue(Files.readString(file, StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof Map<?, ?> report)) {
                throw new IllegalStateException("evaluation report is not a JSON object");
            }
            return report;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评测报告格式无效");
        }
    }

    /** 报告归档前脱敏，规则与 scripts/EvalJob.java 保持一致。 */
    static Object redact(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        return text
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[PHONE]")
                .replaceAll("(?<!\\d)\\d{17}[\\dXx](?!\\d)", "[ID]")
                .replaceAll("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)", "[IP]")
                .replaceAll("(?<!\\d)\\d{16,19}(?!\\d)", "[NUMBER]");
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String stripTrailingSlash(String value) {
        String text = value == null ? "" : value.strip();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
