package com.ragknowledge.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** Redis 哈希的最小读写接口，便于在单测中使用内存实现。 */
interface UsageStore {
    boolean claim(String key, Duration ttl);

    long increment(String key, String field, long delta, Duration ttl);

    Map<String, Long> entries(String key);
}

@Component
class RedisUsageStore implements UsageStore {
    private final StringRedisTemplate redis;

    RedisUsageStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean claim(String key, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
    }

    @Override
    public long increment(String key, String field, long delta, Duration ttl) {
        Long value = redis.opsForHash().increment(key, field, delta);
        redis.expire(key, ttl);
        return value == null ? delta : value;
    }

    @Override
    public Map<String, Long> entries(String key) {
        var raw = redis.opsForHash().entries(key);
        var result = new TreeMap<String, Long>();
        raw.forEach((field, value) -> result.put(String.valueOf(field), Long.parseLong(String.valueOf(value))));
        return result;
    }
}

/** 按租户、日期、模型和用途计量 Token，attemptId 去重；计量失败只记录日志，不影响问答。 */
@Component
class UsageLedger {
    static final String PURPOSE = "chat";
    private static final Duration ATTEMPT_TTL = Duration.ofDays(2);
    private static final Duration USAGE_TTL = Duration.ofDays(45);
    private static final Logger LOG = LoggerFactory.getLogger(UsageLedger.class);
    private final UsageStore store;
    private final Map<String, BigDecimal[]> prices;
    private final String currency;

    UsageLedger(UsageStore store, List<AnswerModel.Endpoint> endpoints, @Value("${rag.usage-currency}") String currency) {
        Assert.hasText(currency, "RAG_USAGE_CURRENCY is required");
        this.store = store;
        this.currency = currency;
        var pricing = new TreeMap<String, BigDecimal[]>();
        for (AnswerModel.Endpoint endpoint : endpoints) {
            pricing.put(endpoint.model(),
                    new BigDecimal[]{endpoint.priceInputPerMillion(), endpoint.priceOutputPerMillion()});
        }
        this.prices = Map.copyOf(pricing);
    }

    static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String key(String tenant, String date) {
        return "rag:usage:" + tenant + ":" + date;
    }

    void billed(String tenant, String date, String model, String attemptId, long inputTokens, long outputTokens) {
        try {
            if (!store.claim("rag:attempt:" + attemptId, ATTEMPT_TTL)) {
                return;
            }
            String key = key(tenant, date);
            store.increment(key, model + "|in", inputTokens, USAGE_TTL);
            store.increment(key, model + "|out", outputTokens, USAGE_TTL);
        } catch (DataAccessException failure) {
            LOG.warn("Usage metering for attempt {} failed: {}", attemptId, failure.toString());
        }
    }

    void reconciling(String tenant, String date, String model, String attemptId) {
        try {
            if (!store.claim("rag:attempt:" + attemptId, ATTEMPT_TTL)) {
                return;
            }
            store.increment(key(tenant, date), model + "|reconcile", 1, USAGE_TTL);
        } catch (DataAccessException failure) {
            LOG.warn("Reconciliation marker for attempt {} failed: {}", attemptId, failure.toString());
        }
    }

    UsageReport report(String tenant, String date) {
        Map<String, Long> entries;
        try {
            entries = store.entries(key(tenant, date));
        } catch (DataAccessException failure) {
            throw QuestionFailure.unavailable("USAGE_UNAVAILABLE", "用量数据暂不可用");
        }
        var usage = new TreeMap<String, long[]>();
        entries.forEach((field, value) -> {
            int separator = field.lastIndexOf('|');
            if (separator <= 0 || value == null) {
                return;
            }
            long[] bucket = usage.computeIfAbsent(field.substring(0, separator), key -> new long[3]);
            switch (field.substring(separator + 1)) {
                case "in" -> bucket[0] += value;
                case "out" -> bucket[1] += value;
                case "reconcile" -> bucket[2] += value;
                default -> { }
            }
        });
        List<ModelUsage> models = usage.entrySet().stream().map(entry -> {
            String model = entry.getKey();
            long[] bucket = entry.getValue();
            BigDecimal[] price = prices.getOrDefault(model, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal cost = price[0].multiply(BigDecimal.valueOf(bucket[0]))
                    .add(price[1].multiply(BigDecimal.valueOf(bucket[1])))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
            String estimated = cost.signum() == 0 ? "0" : cost.stripTrailingZeros().toPlainString();
            return new ModelUsage(model, PURPOSE, bucket[0], bucket[1], bucket[2], estimated);
        }).toList();
        return new UsageReport(date, currency, models);
    }

    record UsageReport(String date, String currency, List<ModelUsage> models) {}

    record ModelUsage(String model, String purpose, long inputTokens, long outputTokens, long reconcilingAttempts,
            String estimatedCost) {
    }
}
