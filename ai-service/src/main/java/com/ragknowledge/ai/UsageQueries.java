package com.ragknowledge.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员用量查询：按模型和用途返回 Token、估算费用、币种和待对账次数。 */
@RestController
class UsageQueries {
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private final byte[] authorization;
    private final UsageLedger ledger;

    UsageQueries(UsageLedger ledger, @Value("${rag.gateway-ai-token}") String token) {
        Assert.hasText(token, "RAG_GATEWAY_AI_TOKEN is required");
        this.ledger = ledger;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/usage")
    ResponseEntity<?> usage(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Trace-Id", required = false) String requestId,
            @RequestParam(required = false) String date) {
        String traceId = requestId != null && requestId.matches("[A-Za-z0-9_-]{1,64}") ? requestId
                : UUID.randomUUID().toString();
        if (token == null || !MessageDigest.isEqual(authorization, token.getBytes(StandardCharsets.UTF_8))) {
            return error(401, "UNAUTHORIZED", "内部服务凭证无效", traceId);
        }
        if (tenant == null || !tenant.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            return error(403, "FORBIDDEN", "内部租户上下文无效", traceId);
        }
        if (date == null || !DATE.matcher(date).matches() || !parseable(date)) {
            return error(400, "BAD_REQUEST", "日期应为 YYYY-MM-DD", traceId);
        }
        try {
            return ResponseEntity.ok(ledger.report(tenant, date));
        } catch (QuestionFailure failure) {
            return error(failure.status, failure.code, failure.getMessage(), traceId);
        }
    }

    private static boolean parseable(String date) {
        try {
            LocalDate.parse(date, STRICT_DATE);
            return true;
        } catch (java.time.format.DateTimeParseException invalid) {
            return false;
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message, String traceId) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "traceId", traceId));
    }
}
