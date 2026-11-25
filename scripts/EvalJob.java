import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 离线评测作业：直连 metric-service 逐题评分并归档报告，失败时保留原因和空分数。 */
public class EvalJob {
    private static final String DATASET = "/eval/golden.json";
    private static final String REPORTS = "/eval/reports";

    public static void main(String[] args) throws Exception {
        String base = System.getenv().getOrDefault("RAG_METRIC_URL", "http://metric-service:8090");
        String token = System.getenv().getOrDefault("RAG_METRIC_TOKEN", "");
        if (token.isBlank()) {
            System.err.println("RAG_METRIC_TOKEN is required");
            System.exit(2);
        }
        Object parsed = Json.parse(Files.readString(Path.of(DATASET), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("samples") instanceof List<?> samples)) {
            System.err.println("Invalid evaluation dataset");
            System.exit(2);
            return;
        }
        Files.createDirectories(Path.of(REPORTS));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var results = new ArrayList<Object>();
        String reason = null;
        int index = 0;
        for (; index < samples.size(); index++) {
            var sample = (Map<?, ?>) samples.get(index);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("user_input", redact(sample.get("user_input")));
            try {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("user_input", sample.get("user_input"));
                payload.put("response", sample.get("response"));
                payload.put("retrieved_contexts", sample.get("retrieved_contexts"));
                if (sample.containsKey("reference")) {
                    payload.put("reference", sample.get("reference"));
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/evaluations"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("metric-service returned HTTP " + response.statusCode()
                            + ": " + response.body());
                }
                Object scores = Json.parse(response.body());
                if (!(scores instanceof Map<?, ?> fields)) {
                    throw new IllegalStateException("metric-service returned an invalid score payload");
                }
                entry.put("faithfulness", fields.get("faithfulness"));
                entry.put("context_recall", fields.get("context_recall"));
                entry.put("error", null);
            } catch (Exception failure) {
                reason = failure.toString();
                entry.put("faithfulness", null);
                entry.put("context_recall", null);
                entry.put("error", reason);
                results.add(entry);
                index++;
                break;
            }
            results.add(entry);
        }
        for (; index < samples.size(); index++) {
            var skipped = new LinkedHashMap<String, Object>();
            skipped.put("user_input", redact(((Map<?, ?>) samples.get(index)).get("user_input")));
            skipped.put("faithfulness", null);
            skipped.put("context_recall", null);
            skipped.put("error", "评测中断，未执行");
            results.add(skipped);
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("dataset", root.containsKey("name") ? root.get("name") : "unknown");
        report.put("status", reason == null ? "OK" : "FAILED");
        report.put("redacted", true);
        report.put("reason", reason);
        report.put("generatedAt", System.currentTimeMillis());
        report.put("samples", results);
        report.put("averages", average(results));
        Path output = Path.of(REPORTS, "report-" + System.currentTimeMillis() + ".json");
        Files.writeString(output, Json.write(report), StandardCharsets.UTF_8);
        System.out.println("Evaluation " + report.get("status") + " report=" + output
                + (reason == null ? "" : " reason=" + reason));
        System.exit(reason == null ? 0 : 1);
    }

    /** 报告归档前脱敏；发送给评测服务的原文不变，分数计算不受影响。 */
    private static Object redact(Object value) {
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

    private static Map<String, Object> average(List<Object> results) {
        var averages = new LinkedHashMap<String, Object>();
        for (String metric : List.of("faithfulness", "context_recall")) {
            double sum = 0;
            int count = 0;
            for (Object item : results) {
                Object value = ((Map<?, ?>) item).get(metric);
                if (value instanceof Number number) {
                    sum += number.doubleValue();
                    count++;
                }
            }
            averages.put(metric, count == 0 ? null : sum / count);
        }
        return averages;
    }

    /** 仅覆盖离线评测所需 JSON 子集的递归下降解析器与序列化器。 */
    static final class Json {
        private final String source;
        private int position;

        private Json(String source) {
            this.source = source;
        }

        static Object parse(String text) {
            Json reader = new Json(text);
            reader.skip();
            Object value = reader.value();
            reader.skip();
            if (reader.position != text.length()) {
                throw new IllegalArgumentException("Trailing JSON content");
            }
            return value;
        }

        private void skip() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
        }

        private Object value() {
            skip();
            if (position >= source.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char current = source.charAt(position);
            return switch (current) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            var result = new LinkedHashMap<String, Object>();
            position++;
            skip();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                if (peek() != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + position);
                }
                position++;
                result.put(key, value());
                skip();
                char current = peek();
                position++;
                if (current == '}') {
                    return result;
                }
                if (current != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + position);
                }
            }
        }

        private List<Object> array() {
            var result = new ArrayList<Object>();
            position++;
            skip();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                result.add(value());
                skip();
                char current = peek();
                position++;
                if (current == ']') {
                    return result;
                }
                if (current != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + position);
                }
            }
        }

        private String string() {
            if (peek() != '"') {
                throw new IllegalArgumentException("Expected string at " + position);
            }
            position++;
            var builder = new StringBuilder();
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(source.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape at " + position);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Object literal(String expected, Object value) {
            if (!source.startsWith(expected, position)) {
                throw new IllegalArgumentException("Invalid literal at " + position);
            }
            position += expected.length();
            return value;
        }

        private Number number() {
            int start = position;
            while (position < source.length() && "-+.eE0123456789".indexOf(source.charAt(position)) >= 0) {
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("Invalid number at " + position);
            }
            String text = source.substring(start, position);
            return text.contains(".") || text.contains("e") || text.contains("E")
                    ? Double.parseDouble(text) : Long.parseLong(text);
        }

        private char peek() {
            if (position >= source.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return source.charAt(position);
        }

        static String write(Object value) {
            var builder = new StringBuilder();
            write(value, builder);
            return builder.toString();
        }

        private static void write(Object value, StringBuilder builder) {
            if (value == null) {
                builder.append("null");
            } else if (value instanceof String text) {
                builder.append('"');
                for (int index = 0; index < text.length(); index++) {
                    char current = text.charAt(index);
                    switch (current) {
                        case '"' -> builder.append("\\\"");
                        case '\\' -> builder.append("\\\\");
                        case '\n' -> builder.append("\\n");
                        case '\r' -> builder.append("\\r");
                        case '\t' -> builder.append("\\t");
                        default -> {
                            if (current < 0x20) {
                                builder.append(String.format("\\u%04x", (int) current));
                            } else {
                                builder.append(current);
                            }
                        }
                    }
                }
                builder.append('"');
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
            } else if (value instanceof Map<?, ?> map) {
                builder.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    write(String.valueOf(entry.getKey()), builder);
                    builder.append(':');
                    write(entry.getValue(), builder);
                }
                builder.append('}');
            } else if (value instanceof List<?> list) {
                builder.append('[');
                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    write(list.get(index), builder);
                }
                builder.append(']');
            } else {
                throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
            }
        }
    }
}
