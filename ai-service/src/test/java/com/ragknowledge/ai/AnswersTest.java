package com.ragknowledge.ai;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswersTest {
    private static final RagAnswers.Locator LOCATION = new RagAnswers.Locator("line", 1, 1);
    private static final String QUOTE = "入职满一年，可休 7 天公司年假。";
    private static final RagAnswers.Chunk CHUNK = new RagAnswers.Chunk("tenant-a:42:1:0", "42", "索引标题", 1, QUOTE, LOCATION);
    private static final RagAnswers.Source SOURCE = new RagAnswers.Source("42", "年假政策", 1, LOCATION, QUOTE);

    /** UsageStore 的内存实现，与 Redis 语义一致：claim 仅首次成功，increment 返回累计值。 */
    static class MemoryStore implements UsageStore {
        private final Map<String, String> claimed = new HashMap<>();
        private final Map<String, Map<String, Long>> hashes = new HashMap<>();

        @Override
        public boolean claim(String key, Duration ttl) {
            return claimed.putIfAbsent(key, "1") == null;
        }

        @Override
        public long increment(String key, String field, long delta, Duration ttl) {
            var hash = hashes.computeIfAbsent(key, name -> new HashMap<>());
            return hash.merge(field, delta, Long::sum);
        }

        @Override
        public Map<String, Long> entries(String key) {
            return Map.copyOf(hashes.getOrDefault(key, Map.of()));
        }
    }

    /** 可控的 Chat Completions 测试服务器：mode 决定 200/500/无效输出。 */
    static class StubChat {
        final HttpServer server;
        final AtomicReference<String> mode = new AtomicReference<>("ok");
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastRequest = new AtomicReference<>();
        private final JsonMapper json = new JsonMapper();

        StubChat() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                calls.incrementAndGet();
                try {
                    lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes;
                    int status = switch (mode.get()) {
                        case "500" -> 500;
                        default -> 200;
                    };
                    if (status == 500) {
                        bytes = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                    } else if ("garbage".equals(mode.get())) {
                        bytes = "not json".getBytes(StandardCharsets.UTF_8);
                    } else {
                        var completion = completion("tenant-a:42:1:0", 1, QUOTE);
                        bytes = json.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                                Map.of("content", json.writeValueAsString(completion)))),
                                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 4)))
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        AnswerModel.Endpoint endpoint(String model, String tag) {
            return new AnswerModel.Endpoint(url(), model, "", BigDecimal.valueOf(2), BigDecimal.valueOf(6), tag);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
        }

        void close() {
            server.stop(0);
        }
    }

    private static UsageLedger ledger(UsageStore store, AnswerModel.Endpoint... endpoints) {
        return new UsageLedger(store, List.of(endpoints), "CNY");
    }

    private static AnswerModel model(UsageLedger ledger, CircuitBreakers breakers, List<AnswerModel.Endpoint> endpoints) {
        // true/"" = 默认 JSON 模式、不发送 thinking 参数，与线上默认配置一致
        return new AnswerModel(endpoints, 4000, 40_000, true, "", ledger, breakers, new JsonMapper());
    }

    private static CircuitBreakers breakers(AtomicLong clock) {
        return new CircuitBreakers(2, 8000, clock::get);
    }

    @Test
    void citationsMustMatchTheRetrievedVersionAndTheAuthoritativeText() {
        var valid = RagAnswers.validate(completion(CHUNK.chunkId(), 1, QUOTE), List.of(CHUNK), hit -> SOURCE, "trace");
        assertEquals(QUOTE + " [1]", valid.answer());
        assertEquals(SOURCE.title(), valid.citations().getFirst().title());
        assertEquals(LOCATION, valid.citations().getFirst().locator());
        for (var item : List.of(completion("unknown", 1, QUOTE), completion(CHUNK.chunkId(), 2, QUOTE),
                completion(CHUNK.chunkId(), 1, "可休 9 天"))) {
            assertEquals(502, assertThrows(QuestionFailure.class,
                    () -> RagAnswers.validate(item, List.of(CHUNK), hit -> SOURCE, "trace")).status);
        }
        var differentSource = new RagAnswers.Source("42", SOURCE.title(), 1, LOCATION, "Different authoritative text");
        assertEquals(502, assertThrows(QuestionFailure.class, () -> RagAnswers.validate(
                completion(CHUNK.chunkId(), 1, QUOTE), List.of(CHUNK), hit -> differentSource, "trace")).status);
        assertEquals(503, assertThrows(QuestionFailure.class, () -> RagAnswers.validate(
                completion(CHUNK.chunkId(), 1, QUOTE), List.of(CHUNK), hit -> null, "trace")).status);
    }

    @Test
    void localFixtureRefusesUnrelatedEvidence() {
        var model = model(ledger(new MemoryStore()), breakers(new AtomicLong()), List.of());
        var reply = model.generate("tenant-a", "海外差旅每天的住宿预算是多少？", List.of(CHUNK), "trace");
        var result = RagAnswers.validate(reply, List.of(CHUNK), hit -> SOURCE, "trace");
        assertEquals("INSUFFICIENT_EVIDENCE", result.status());
        assertEquals(List.of(), result.citations());
    }

    @Test
    void meteringCountsEachAttemptOnceAndPricesByModel() {
        var store = new MemoryStore();
        var ledger = ledger(store, new AnswerModel.Endpoint("http://unused/chat", "fixture", "",
                BigDecimal.valueOf(2), BigDecimal.valueOf(6), "primary"));
        String date = UsageLedger.today();
        ledger.billed("tenant-a", date, "fixture", "trace:primary", 1_000_000, 500_000);
        ledger.billed("tenant-a", date, "fixture", "trace:primary", 1_000_000, 500_000);
        ledger.reconciling("tenant-a", date, "fixture", "trace:timeout");
        ledger.reconciling("tenant-a", date, "fixture", "trace:timeout");
        var report = ledger.report("tenant-a", date);
        assertEquals("CNY", report.currency());
        var entry = report.models().getFirst();
        assertEquals("fixture", entry.model());
        assertEquals(1_000_000, entry.inputTokens());
        assertEquals(500_000, entry.outputTokens());
        assertEquals(1, entry.reconcilingAttempts());
        assertEquals("5", entry.estimatedCost());
        assertEquals(List.of(), ledger.report("tenant-b", date).models());
    }

    @Test
    void rateLimiterBlocksOverQuotaWithRetryAfter() {
        var clock = new AtomicLong(61_000);
        var limiter = new RateLimiter(new MemoryStore(), 1, clock::get);
        var first = limiter.check("tenant-a");
        assertTrue(first.allowed());
        var denied = limiter.check("tenant-a");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1 && denied.retryAfterSeconds() <= 60);
        limiter.refund(first);
        assertTrue(limiter.check("tenant-b").allowed());
        clock.addAndGet(60_000);
        assertTrue(limiter.check("tenant-a").allowed());
    }

    @Test
    void primaryFailureFallsBackToBackupAndMetersTheBackup() throws Exception {
        var primary = new StubChat();
        var backup = new StubChat();
        try {
            primary.mode.set("500");
            var store = new MemoryStore();
            var backupEndpoint = backup.endpoint("fixture-backup", "backup");
            var ledger = ledger(store, backupEndpoint);
            var model = model(ledger, breakers(new AtomicLong()),
                    List.of(primary.endpoint("fixture-primary", "primary"), backupEndpoint));
            var completion = model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-a");
            assertEquals("ANSWERED", completion.status());
            assertEquals(1, primary.calls.get());
            assertEquals(1, backup.calls.get());
            var report = ledger.report("tenant-a", UsageLedger.today());
            assertEquals(1, report.models().size());
            assertEquals("fixture-backup", report.models().getFirst().model());
            assertEquals(10, report.models().getFirst().inputTokens());

            // 双端点故障 → 503
            backup.mode.set("500");
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-b")).status);
        } finally {
            primary.close();
            backup.close();
        }
    }

    @Test
    void failoverNeedsEnoughBudgetForAFullModelTimeout() throws Exception {
        var primary = new StubChat();
        var backup = new StubChat();
        try {
            primary.mode.set("500");
            var model = model(ledger(new MemoryStore()), breakers(new AtomicLong()),
                    List.of(primary.endpoint("fixture-primary", "primary"), backup.endpoint("fixture-backup", "backup")));
            // 剩余 1 秒，不够等完 4 秒的模型超时：不切备，备用端点既不被调用也不计费
            long nearlyOut = System.nanoTime() + 1_000_000_000L;
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-a", nearlyOut)).status);
            assertEquals(1, primary.calls.get());
            assertEquals(0, backup.calls.get());
            // 剩余充足时照常切备
            var completion = model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-b",
                    System.nanoTime() + 30_000_000_000L);
            assertEquals("ANSWERED", completion.status());
            assertEquals(1, backup.calls.get());
        } finally {
            primary.close();
            backup.close();
        }
    }

    @Test
    void firstAttemptIgnoresAnExhaustedBudget() throws Exception {
        var primary = new StubChat();
        try {
            var model = model(ledger(new MemoryStore()), breakers(new AtomicLong()),
                    List.of(primary.endpoint("fixture-primary", "primary")));
            // 检索已经完成，首次调用不因预算用尽而放弃
            var completion = model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace", System.nanoTime() - 1);
            assertEquals("ANSWERED", completion.status());
            assertEquals(1, primary.calls.get());
        } finally {
            primary.close();
        }
    }

    @Test
    void budgetSkipDoesNotConsumeTheHalfOpenProbe() throws Exception {
        var primary = new StubChat();
        var backup = new StubChat();
        try {
            var clock = new AtomicLong(1_000_000);
            var breakers = breakers(clock);
            breakers.failure("backup");
            breakers.failure("backup");
            clock.addAndGet(8000);
            primary.mode.set("500");
            var model = model(ledger(new MemoryStore()), breakers,
                    List.of(primary.endpoint("fixture-primary", "primary"), backup.endpoint("fixture-backup", "backup")));
            long nearlyOut = System.nanoTime() + 1_000_000_000L;
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace", nearlyOut)).status);
            assertEquals(0, backup.calls.get());
            // 备用端点冷却已结束，因预算被跳过时探测名额必须仍在
            assertTrue(breakers.allows("backup"));
        } finally {
            primary.close();
            backup.close();
        }
    }

    @Test
    void invalidModelOutputDoesNotFailOver() throws Exception {
        var primary = new StubChat();
        var backup = new StubChat();
        try {
            primary.mode.set("garbage");
            var model = model(ledger(new MemoryStore()), breakers(new AtomicLong()),
                    List.of(primary.endpoint("fixture-primary", "primary"), backup.endpoint("fixture-backup", "backup")));
            assertEquals(502, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace")).status);
            assertEquals(1, primary.calls.get());
            assertEquals(0, backup.calls.get());
        } finally {
            primary.close();
            backup.close();
        }
    }

    @Test
    void halfOpenAdmitsOnlyOneProbeUntilItReports() {
        var clock = new AtomicLong(1_000_000);
        var breakers = breakers(clock);
        breakers.failure("primary");
        breakers.failure("primary");
        assertFalse(breakers.allows("primary"));
        clock.addAndGet(8000);
        // 冷却结束：只有第一个请求成为探测，并发请求继续短路
        assertTrue(breakers.allows("primary"));
        assertFalse(breakers.allows("primary"));
        // 探测失败后重新打开，下一个冷却期结束再放行一次
        breakers.failure("primary");
        assertFalse(breakers.allows("primary"));
        clock.addAndGet(8000);
        assertTrue(breakers.allows("primary"));
        assertFalse(breakers.allows("primary"));
        // 探测成功后恢复为全量放行
        breakers.success("primary");
        assertTrue(breakers.allows("primary"));
        assertTrue(breakers.allows("primary"));
    }

    @Test
    void breakerOpensAfterThresholdAndRecoversThroughHalfOpenProbe() throws Exception {
        var primary = new StubChat();
        try {
            primary.mode.set("500");
            var clock = new AtomicLong(1_000_000);
            var model = model(ledger(new MemoryStore()), breakers(clock),
                    List.of(primary.endpoint("fixture", "primary")));
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-a")).status);
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-b")).status);
            assertEquals(2, primary.calls.get());
            // 熔断已打开：第三次请求不再访问供应商
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-c")).status);
            assertEquals(2, primary.calls.get());
            // 冷却结束但供应商仍失败：半开探测后立即重新打开
            clock.addAndGet(9000);
            assertEquals(503, assertThrows(QuestionFailure.class,
                    () -> model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-d")).status);
            assertEquals(3, primary.calls.get());
            // 再次冷却且供应商恢复：半开探测成功并关闭熔断
            clock.addAndGet(9000);
            primary.mode.set("ok");
            assertEquals("ANSWERED", model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-e").status());
            assertEquals(4, primary.calls.get());
            assertEquals("ANSWERED", model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-f").status());
            assertEquals(5, primary.calls.get());
        } finally {
            primary.close();
        }
    }

    @Test
    void configuredEndpointHandlesUnicodeAndRecordsUsage() throws Exception {
        var server = new StubChat();
        try {
            var store = new MemoryStore();
            var endpoint = server.endpoint("fixture", "primary");
            var ledger = ledger(store, endpoint);
            var model = model(ledger, breakers(new AtomicLong()), List.of(endpoint));
            var generation = model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace");
            assertEquals(QUOTE, generation.citations().getFirst().quote());
            var json = new JsonMapper();
            String input = json.readTree(server.lastRequest.get()).path("messages").path(1).path("content").stringValue();
            assertEquals("年假几天？", json.readTree(input).path("question").stringValue());
            var report = ledger.report("tenant-a", UsageLedger.today());
            assertEquals(10, report.models().getFirst().inputTokens());
            assertEquals(4, report.models().getFirst().outputTokens());
        } finally {
            server.close();
        }
    }

    @Test
    void nonStringQuestionReturnsBadRequest() throws Exception {
        var json = new JsonMapper();
        var answers = new RagAnswers(model(ledger(new MemoryStore()), breakers(new AtomicLong()), List.of()),
                org.springframework.web.client.RestClient.builder(), "search-token", "doc-token");
        var questions = new Questions(answers, new RateLimiter(new MemoryStore(), 60, System::currentTimeMillis), "gateway-token");
        for (String payload : new String[]{"{\"question\":17}", "{\"question\":null}", "{\"question\":[\"年假\"]}", "{}"}) {
            assertEquals(400, questions.ask("Bearer gateway-token", "tenant-a", null, json.readTree(payload))
                    .getStatusCode().value(), payload);
        }
        assertEquals(400, questions.ask("Bearer gateway-token", "tenant-a", null, null).getStatusCode().value());
    }

    @Test
    void dependencyFailureRefundsTenantQuota() throws Exception {
        var json = new JsonMapper();
        var answers = new RagAnswers(model(ledger(new MemoryStore()), breakers(new AtomicLong()), List.of()),
                org.springframework.web.client.RestClient.builder(), "search-token", "doc-token");
        var questions = new Questions(answers, new RateLimiter(new MemoryStore(), 1, new AtomicLong(61_000)::get), "gateway-token");
        var body = json.readTree("{\"question\":\"年假几天？\"}");
        // 检索不可用时返回 503 并归还配额，连续故障不会误触发 429
        assertEquals(503, questions.ask("Bearer gateway-token", "tenant-a", null, body).getStatusCode().value());
        assertEquals(503, questions.ask("Bearer gateway-token", "tenant-a", null, body).getStatusCode().value());
    }

    @Test
    void conversationHistoryIsSentToModelButNeverIntoEvidence() throws Exception {
        var server = new StubChat();
        try {
            var store = new MemoryStore();
            var endpoint = server.endpoint("fixture", "primary");
            var ledger = ledger(store, endpoint);
            var model = model(ledger, breakers(new AtomicLong()), List.of(endpoint));
            var history = List.of(new RagAnswers.Turn("出差住宿标准是多少？", "一线城市不超过 600 元。"));
            var generation = model.generate("tenant-a", "那差旅补贴呢？", history, List.of(CHUNK), "trace", model.deadline());
            assertEquals("ANSWERED", generation.status());
            String input = new JsonMapper().readTree(server.lastRequest.get())
                    .path("messages").path(1).path("content").stringValue();
            var payload = new JsonMapper().readTree(input);
            assertEquals("那差旅补贴呢？", payload.path("question").stringValue());
            assertEquals("出差住宿标准是多少？", payload.path("history").path(0).path("question").stringValue());
            assertEquals("一线城市不超过 600 元。", payload.path("history").path(0).path("answer").stringValue());
            // 上文只作提示词上下文：证据仍只有本次检索到的片段
            assertEquals(1, payload.path("evidence").size());
            assertEquals(CHUNK.chunkId(), payload.path("evidence").path(0).path("chunkId").stringValue());
            // 无上文时保持旧载荷形状，避免兼容性回退
            model.generate("tenant-a", "年假几天？", List.of(CHUNK), "trace-2");
            String bare = new JsonMapper().readTree(server.lastRequest.get())
                    .path("messages").path(1).path("content").stringValue();
            assertFalse(new JsonMapper().readTree(bare).has("history"));
        } finally {
            server.close();
        }
    }

    @Test
    void retrievalQueryKeepsLastTwoTurnsForReferenceResolution() {
        var history = List.of(new RagAnswers.Turn("第一个问题", ""), new RagAnswers.Turn("出差住宿标准是多少？", ""),
                new RagAnswers.Turn("那差旅补贴呢？", ""));
        assertEquals("出差住宿标准是多少？ 那差旅补贴呢？ 它适用于谁", RagAnswers.retrievalQuery(history, "它适用于谁"));
        assertEquals("年假几天？", RagAnswers.retrievalQuery(List.of(), "年假几天？"));
        String longQuery = RagAnswers.retrievalQuery(List.of(new RagAnswers.Turn("很长的历史问题".repeat(60), "")),
                "很长的追问".repeat(60));
        assertEquals(300, longQuery.length());
        assertTrue(longQuery.endsWith("很长的追问".repeat(12)));
    }

    private static AnswerModel.Completion completion(String id, int version, String quote) {
        return new AnswerModel.Completion("ANSWERED", QUOTE, List.of(new AnswerModel.ModelCitation(id, version, quote)));
    }
}
