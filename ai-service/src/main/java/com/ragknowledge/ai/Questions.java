package com.ragknowledge.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.Assert;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
class Questions {
    private static final Logger LOG = LoggerFactory.getLogger(Questions.class);
    /** 会话上文只用于消解指代，保留最近 4 轮并对长度截断，避免提示词被历史淹没。 */
    private static final int MAX_HISTORY_TURNS = 4;
    private static final int MAX_HISTORY_QUESTION = 300;
    private static final int MAX_HISTORY_ANSWER = 600;
    private static final int MAX_HISTORY_ITEMS = 20;
    private final byte[] authorization;
    private final RagAnswers answers;
    private final RateLimiter quota;

    Questions(RagAnswers answers, RateLimiter quota, @Value("${rag.gateway-ai-token}") String token) {
        Assert.hasText(token, "RAG_GATEWAY_AI_TOKEN is required");
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.answers = answers;
        this.quota = quota;
    }

    @PostMapping(path = "/internal/v1/questions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> ask(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Trace-Id", required = false) String requestId,
            @RequestBody(required = false) JsonNode body) {
        String traceId = trace(requestId);
        MDC.put("traceId", traceId);
        long started = System.nanoTime();
        try {
            ResponseEntity<?> result = dispatch(traceId, token, tenant, body);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            String outcome = outcome(result);
            RagMetrics.question(durationMs, outcome);
            LOG.info("question traceId={} tenant={} outcome={} durationMs={}", traceId, tenant, outcome, durationMs);
            return result;
        } finally {
            MDC.remove("traceId");
        }
    }

    private ResponseEntity<?> dispatch(String traceId, String token, String tenant, JsonNode body) {
        if (token == null || !MessageDigest.isEqual(authorization, token.getBytes(StandardCharsets.UTF_8))) {
            return error(401, "UNAUTHORIZED", "内部服务凭证无效", traceId);
        }
        if (tenant == null || !tenant.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            return error(403, "FORBIDDEN", "内部租户上下文无效", traceId);
        }
        JsonNode field = body == null ? null : body.path("question");
        String question = field == null || !field.isString() ? null : field.stringValue();
        if (question == null || question.isBlank() || question.strip().length() > 1000) {
            return error(400, "BAD_REQUEST", "请输入 1 到 1000 个字符的问题", traceId);
        }
        try {
            RateLimiter.Decision decision = quota.check(tenant);
            if (!decision.allowed()) {
                return ResponseEntity.status(429)
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                        .body(Map.of("code", "QUOTA_EXCEEDED", "message", "已达到租户问答限额，请稍后重试", "traceId", traceId));
            }
            try {
                return ResponseEntity.ok(answers.ask(tenant, question.strip(), history(body), traceId));
            } catch (QuestionFailure failure) {
                if (failure.status == 503) {
                    quota.refund(decision);
                }
                throw failure;
            }
        } catch (QuestionFailure failure) {
            LOG.warn("Question {} returned {} ({})", traceId, failure.status, failure.code);
            RagMetrics.count("rag.answer.failures", "code", failure.code);
            return error(failure.status, failure.code, failure.getMessage(), traceId);
        }
    }

    /** 解析可选的会话上文：非法条目直接跳过，保证旧客户端只发 {question} 时行为不变。 */
    private static List<RagAnswers.Turn> history(JsonNode body) {
        JsonNode field = body == null ? null : body.path("history");
        if (field == null || !field.isArray() || field.isEmpty()) {
            return List.of();
        }
        var turns = new ArrayList<RagAnswers.Turn>();
        int examined = 0;
        for (JsonNode item : field) {
            if (++examined > MAX_HISTORY_ITEMS) {
                break;
            }
            if (item == null || !item.isObject()) {
                continue;
            }
            JsonNode question = item.path("question");
            JsonNode answer = item.path("answer");
            if (!question.isString() || !answer.isString()) {
                continue;
            }
            String text = clamp(question.stringValue().strip(), MAX_HISTORY_QUESTION);
            if (text.isEmpty()) {
                continue;
            }
            turns.add(new RagAnswers.Turn(text, clamp(answer.stringValue().strip(), MAX_HISTORY_ANSWER)));
            if (turns.size() > MAX_HISTORY_TURNS) {
                turns.removeFirst();
            }
        }
        return List.copyOf(turns);
    }

    private static String clamp(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String outcome(ResponseEntity<?> response) {
        int status = response.getStatusCode().value();
        if (status == 200 && response.getBody() instanceof RagAnswers.Answer answer) {
            return answer.status();
        }
        if (status == 429) {
            return "QUOTA_EXCEEDED";
        }
        return status >= 500 ? "HTTP_5XX" : status >= 400 ? "HTTP_4XX" : "OK";
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformed(HttpServletRequest request) {
        return error(400, "BAD_REQUEST", "问题请求必须为有效的 JSON", trace(request.getHeader("X-Trace-Id")));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> wrongType(HttpServletRequest request) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json 提交问题", trace(request.getHeader("X-Trace-Id")));
    }

    private static String trace(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}") ? id : UUID.randomUUID().toString();
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message, String traceId) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "traceId", traceId));
    }
}
