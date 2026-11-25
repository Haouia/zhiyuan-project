package com.ragknowledge.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
class RagAnswers {
    private static final Logger LOG = LoggerFactory.getLogger(RagAnswers.class);
    private static final Pattern MODEL_MARKER = Pattern.compile("\\[\\s*\\d+\\s*]");
    private static final Set<String> UNITS = Set.of("line", "page", "paragraph");
    private static final String INSUFFICIENT = "现有资料不足以回答这个问题。";
    private final AnswerModel model;
    private final RestClient search;
    private final RestClient sources;

    RagAnswers(AnswerModel model, @LoadBalanced RestClient.Builder builder,
            @Value("${rag.ai-search-token}") String searchToken, @Value("${rag.ai-doc-token}") String documentToken) {
        Assert.hasText(searchToken, "RAG_AI_SEARCH_TOKEN is required");
        Assert.hasText(documentToken, "RAG_AI_DOC_TOKEN is required");
        Assert.isTrue(!searchToken.equals(documentToken), "Internal credentials must be distinct");
        this.model = model;
        this.search = builder.clone().baseUrl("http://search-service").requestFactory(QuestionClients.http(Duration.ofSeconds(22)))
                .defaultHeaders(headers -> headers.setBearerAuth(searchToken)).build();
        this.sources = builder.clone().baseUrl("http://doc-service").requestFactory(QuestionClients.http(Duration.ofSeconds(4)))
                .defaultHeaders(headers -> headers.setBearerAuth(documentToken)).build();
    }

    Answer ask(String tenant, String question, String traceId) {
        return ask(tenant, question, List.of(), traceId);
    }

    /**
     * history 只参与两处：检索查询的指代补全，以及提示词里的会话上文；
     * 它不进入 evidence，因此引用校验依旧只认可本次检索到的片段。
     */
    Answer ask(String tenant, String question, List<Turn> history, String traceId) {
        long deadline = model.deadline();
        List<Chunk> evidence = retrieve(tenant, history, question, traceId);
        if (evidence.isEmpty()) {
            return insufficient(traceId);
        }
        AnswerModel.Completion completion = model.generate(tenant, question, history, evidence, traceId, deadline);
        return validate(completion, evidence, chunk -> source(tenant, traceId, chunk), traceId);
    }

    /** 把最近两轮问题拼进检索查询，用于消解「那差旅呢」「它适用于谁」之类的指代。 */
    static String retrievalQuery(List<Turn> history, String question) {
        if (history.isEmpty()) {
            return question;
        }
        var context = new StringBuilder();
        for (Turn turn : history.subList(Math.max(0, history.size() - 2), history.size())) {
            if (!context.isEmpty()) {
                context.append(' ');
            }
            context.append(turn.question());
        }
        if (!context.isEmpty()) {
            context.append(' ');
        }
        context.append(question);
        String query = context.toString();
        return query.length() <= 300 ? query : query.substring(query.length() - 300);
    }

    private List<Chunk> retrieve(String tenant, List<Turn> history, String question, String traceId) {
        Retrieval result;
        try {
            result = search.post().uri("/internal/v1/retrieval").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", tenant).header("X-Trace-Id", traceId)
                    .body(Map.of("question", retrievalQuery(history, question), "topK", 5)).retrieve().body(Retrieval.class);
        } catch (RestClientException | IllegalStateException failure) {
            throw retrievalUnavailable();
        }
        if (result == null || result.evidence() == null || result.evidence().size() > 5) {
            throw retrievalUnavailable();
        }
        var ids = new HashSet<String>();
        for (Chunk chunk : result.evidence()) {
            if (chunk == null || chunk.chunkId() == null || !chunk.chunkId().startsWith(tenant + ":" + chunk.documentId() + ":" + chunk.version() + ":")
                    || !ids.add(chunk.chunkId()) || chunk.documentId() == null || !chunk.documentId().matches("[1-9][0-9]{0,18}")
                    || chunk.version() < 1 || chunk.title() == null || chunk.text() == null || chunk.text().isBlank()
                    || chunk.text().length() > 800 || !valid(chunk.locator())) {
                throw retrievalUnavailable();
            }
        }
        return result.evidence();
    }

    private Source source(String tenant, String traceId, Chunk evidence) {
        Locator location = evidence.locator();
        try {
            return sources.get().uri("/internal/v1/documents/{id}/versions/{version}/source?unit={unit}&start={start}&end={end}",
                            evidence.documentId(), evidence.version(), location.unit(), location.start(), location.end())
                    .header("X-Tenant-Id", tenant).header("X-Trace-Id", traceId).retrieve().body(Source.class);
        } catch (RestClientException | IllegalStateException failure) {
            throw QuestionFailure.unavailable("SOURCE_UNAVAILABLE", "版本原文暂不可核验");
        }
    }

    static Answer validate(AnswerModel.Completion output, List<Chunk> evidence, Function<Chunk, Source> sources, String traceId) {
        if (output == null || output.citations() == null) {
            throw rejected(traceId, "output-missing");
        }
        if ("INSUFFICIENT_EVIDENCE".equals(output.status()) && output.citations().isEmpty()) {
            return insufficient(traceId);
        }
        if (!"ANSWERED".equals(output.status()) || output.answer() == null || output.answer().isBlank()
                || output.answer().length() > 1600 || MODEL_MARKER.matcher(output.answer()).find()
                || output.citations().isEmpty() || output.citations().size() > 5) {
            throw rejected(traceId, "shape", "status=" + output.status()
                    + " answerLength=" + (output.answer() == null ? 0 : output.answer().length())
                    + " citations=" + output.citations().size());
        }
        var indexed = new HashMap<String, Chunk>();
        evidence.forEach(chunk -> indexed.put(chunk.chunkId(), chunk));
        var selected = new HashSet<String>();
        var verified = new ArrayList<Citation>();
        for (AnswerModel.ModelCitation candidate : output.citations()) {
            if (candidate == null) {
                throw rejected(traceId, "citation-missing");
            }
            // 真实模型可能对同一片段给出两段不同引文（例如数字与例外条件分处两句），
            // 只要每段都能在片段与原文中逐字核对即可；完全重复的引文直接跳过。
            Chunk chunk = indexed.get(candidate.chunkId());
            if (chunk != null && candidate.quote() != null
                    && !selected.add(candidate.chunkId() + "\u0000" + candidate.quote())) {
                continue;
            }
            if (chunk == null) {
                throw rejected(traceId, "unknown-chunk", candidate.chunkId());
            }
            if (chunk.version() != candidate.version()) {
                throw rejected(traceId, "version-mismatch", candidate.chunkId() + " v" + candidate.version());
            }
            if (candidate.quote() == null || candidate.quote().isBlank() || !chunk.text().contains(candidate.quote())) {
                throw rejected(traceId, "quote-not-in-chunk", candidate.chunkId() + " <<" + abbreviate(candidate.quote()) + ">>");
            }
            Source source = sources.apply(chunk);
            if (source == null || !chunk.documentId().equals(source.documentId()) || chunk.version() != source.version()
                    || !chunk.locator().equals(source.locator()) || source.title() == null || source.text() == null) {
                throw QuestionFailure.unavailable("SOURCE_UNAVAILABLE", "版本原文暂不可核验");
            }
            if (!source.text().contains(candidate.quote())) {
                throw rejected(traceId, "quote-not-in-source", candidate.chunkId());
            }
            verified.add(new Citation(source.documentId(), source.title(), source.version(), chunk.chunkId(), source.locator(), candidate.quote()));
        }
        var answer = new StringBuilder(output.answer().strip());
        for (int index = 1; index <= verified.size(); index++) {
            answer.append(' ').append('[').append(index).append(']');
        }
        return new Answer("ANSWERED", answer.toString(), verified, traceId);
    }

    /** 记录被拒原因：真实模型偶尔改写引用或省略标点，日志是定位这类问题最直接的证据。 */
    private static QuestionFailure rejected(String traceId, String reason, Object... detail) {
        LOG.warn("answer traceId={} rejected={} detail={}", traceId, reason, detail.length == 0 ? "-" : detail[0]);
        return QuestionFailure.invalidModel();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    private static boolean valid(Locator location) {
        return location != null && location.unit() != null && UNITS.contains(location.unit())
                && location.start() > 0 && location.end() >= location.start();
    }

    private static QuestionFailure retrievalUnavailable() {
        return QuestionFailure.unavailable("RETRIEVAL_UNAVAILABLE", "检索或版本核验暂不可用");
    }

    private static Answer insufficient(String traceId) {
        return new Answer("INSUFFICIENT_EVIDENCE", INSUFFICIENT, List.of(), traceId);
    }

    record Retrieval(List<Chunk> evidence) {}

    record Locator(String unit, int start, int end) {}

    record Chunk(String chunkId, String documentId, String title, int version, String text, Locator locator) {}

    record Source(String documentId, String title, int version, Locator locator, String text) {}

    record Citation(String documentId, String title, int version, String chunkId, Locator locator, String quote) {}

    record Answer(String status, String answer, List<Citation> citations, String traceId) {}

    record Turn(String question, String answer) {}
}

@Configuration(proxyBeanMethods = false)
class QuestionClients {
    @Bean
    @LoadBalanced
    RestClient.Builder internalQuestionClients() {
        return RestClient.builder();
    }

    static JdkClientHttpRequestFactory http(Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}

class QuestionFailure extends RuntimeException {
    final int status;
    final String code;

    private QuestionFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static QuestionFailure unavailable(String code, String message) {
        return new QuestionFailure(503, code, message);
    }

    static QuestionFailure invalidModel() {
        return new QuestionFailure(502, "INVALID_MODEL_OUTPUT", "模型回答或引用未通过校验");
    }
}
