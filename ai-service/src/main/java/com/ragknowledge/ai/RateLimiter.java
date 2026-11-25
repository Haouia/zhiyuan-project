package com.ragknowledge.ai;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 租户级固定窗口限流：每分钟一个 Redis 计数窗口，超限返回 429 与 Retry-After。 */
@Component
class RateLimiter {
    private final UsageStore store;
    private final long limit;
    private final LongSupplier clock;

    RateLimiter(UsageStore store, @Value("${rag.tenant-question-limit}") long limit, LongSupplier clock) {
        Assert.isTrue(limit >= 1, "RAG_TENANT_QUESTION_LIMIT must be positive");
        this.store = store;
        this.limit = limit;
        this.clock = clock;
    }

    Decision check(String tenant) {
        long now = clock.getAsLong() / 1000;
        String key = "rag:rate:" + tenant + ":" + now / 60;
        long count;
        try {
            count = store.increment(key, "questions", 1, Duration.ofSeconds(120));
        } catch (DataAccessException failure) {
            throw QuestionFailure.unavailable("USAGE_UNAVAILABLE", "租户限额暂不可用");
        }
        if (count <= limit) {
            return new Decision(true, 0, key);
        }
        return new Decision(false, Math.max(1, 60 - now % 60), key);
    }

    /** 依赖故障（503）未实际使用模型额度，归还本次计数。 */
    void refund(Decision decision) {
        try {
            store.increment(decision.windowKey(), "questions", -1, Duration.ofSeconds(120));
        } catch (DataAccessException ignored) {
            // 计数归还失败只影响限额精度，不覆盖原始故障
        }
    }

    record Decision(boolean allowed, long retryAfterSeconds, String windowKey) {
    }
}
