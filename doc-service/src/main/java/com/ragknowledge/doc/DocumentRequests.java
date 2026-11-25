package com.ragknowledge.doc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import tools.jackson.databind.json.JsonMapper;

@Component
class DocumentRequests extends OncePerRequestFilter {
    static final String TENANT = "verifiedTenant";
    static final String SEARCH_TENANT = "verifiedSearchTenant";
    static final String SOURCE_TENANT = "verifiedSourceTenant";
    static final String TRACE = "verifiedTrace";
    private static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final byte[] gatewayAuthorization;
    private final byte[] searchAuthorization;
    private final byte[] aiAuthorization;
    private final JsonMapper json;

    DocumentRequests(@Value("${rag.gateway-doc-token}") String gatewayToken,
            @Value("${rag.search-doc-token}") String searchToken,
            @Value("${rag.ai-doc-token}") String aiToken, JsonMapper json) {
        Assert.hasText(gatewayToken, "RAG_GATEWAY_DOC_TOKEN is required");
        Assert.hasText(searchToken, "RAG_SEARCH_DOC_TOKEN is required");
        Assert.hasText(aiToken, "RAG_AI_DOC_TOKEN is required");
        Assert.isTrue(!gatewayToken.equals(searchToken) && !gatewayToken.equals(aiToken) && !searchToken.equals(aiToken),
                "Internal callers need distinct credentials");
        this.gatewayAuthorization = bearer(gatewayToken);
        this.searchAuthorization = bearer(searchToken);
        this.aiAuthorization = bearer(aiToken);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/documents");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String path = request.getRequestURI();
        boolean parsed = path.endsWith("/parsed");
        boolean activeVersions = "/internal/v1/documents/active-versions".equals(path);
        boolean search = parsed || activeVersions;
        boolean source = path.endsWith("/source");
        byte[] actual = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        // 解析正文既供检索服务消费，也供管理页文档详情经网关读取；生效版本查询仍只允许检索服务。
        boolean allowed = search
                ? MessageDigest.isEqual(searchAuthorization, actual)
                        || (parsed && MessageDigest.isEqual(gatewayAuthorization, actual))
                : MessageDigest.isEqual(gatewayAuthorization, actual) || (source && MessageDigest.isEqual(aiAuthorization, actual));
        if (!allowed) {
            reject(response, 401, "UNAUTHORIZED", "内部服务凭证无效", traceId);
            return;
        }
        String tenant = request.getHeader("X-Tenant-Id");
        if (tenant == null || !TENANT_ID.matcher(tenant).matches()) {
            reject(response, 403, "FORBIDDEN", "内部租户上下文无效", traceId);
            return;
        }
        request.setAttribute(search ? SEARCH_TENANT : source ? SOURCE_TENANT : TENANT, tenant);
        request.setAttribute(TRACE, traceId);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String message, String traceId)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(code, message, traceId));
    }

    private static byte[] bearer(String token) {
        return ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    record ApiError(String code, String message, String traceId) {}
}

@RestControllerAdvice
class DocumentErrors {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentErrors.class);

    @ExceptionHandler(DocumentProblem.class)
    ResponseEntity<DocumentRequests.ApiError> documentError(DocumentProblem problem, HttpServletRequest request) {
        return respond(problem.status, problem.code, problem.getMessage(), traceId(request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<DocumentRequests.ApiError> tooLarge(HttpServletRequest request) {
        return respond(413, "FILE_TOO_LARGE", "单个文件最大为 10 MiB", traceId(request));
    }

    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class,
            MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<DocumentRequests.ApiError> badRequest(HttpServletRequest request) {
        return respond(400, "BAD_REQUEST", "请检查上传表单和请求参数", traceId(request));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<DocumentRequests.ApiError> unsupported(HttpServletRequest request) {
        return respond(415, "UNSUPPORTED_FORMAT", "请使用 multipart/form-data 上传文件", traceId(request));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<DocumentRequests.ApiError> databaseError(DataAccessException error, HttpServletRequest request) {
        String traceId = traceId(request);
        LOG.warn("Document request {} failed ({})", traceId, error.getClass().getSimpleName());
        return respond(503, "SERVICE_UNAVAILABLE", "文档库暂不可用", traceId);
    }

    private static String traceId(HttpServletRequest request) {
        Object trace = request.getAttribute(DocumentRequests.TRACE);
        return trace instanceof String value ? value : UUID.randomUUID().toString();
    }

    private static ResponseEntity<DocumentRequests.ApiError> respond(int status, String code, String message, String traceId) {
        return ResponseEntity.status(status).body(new DocumentRequests.ApiError(code, message, traceId));
    }
}

class DocumentProblem extends RuntimeException {
    final int status;
    final String code;

    DocumentProblem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static DocumentProblem badRequest(String message) {
        return new DocumentProblem(400, "BAD_REQUEST", message);
    }

    static DocumentProblem notFound() {
        return new DocumentProblem(404, "NOT_FOUND", "文档或版本不存在");
    }
}
