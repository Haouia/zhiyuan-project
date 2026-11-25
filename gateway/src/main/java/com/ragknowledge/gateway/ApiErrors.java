package com.ragknowledge.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-2)
class ApiErrors implements WebExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    private final JsonMapper json;

    ApiErrors(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/v1/") || exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatusCode status = error instanceof ErrorResponse response ? response.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.value() >= 500) {
            // 5xx 往往来自依赖或文件系统异常，带上类型与消息便于从日志定位，不再只看到状态码
            LOG.warn("gateway traceId={} status={} failure={}: {}", exchange.getRequest().getId(), status.value(),
                    error.getClass().getSimpleName(), error.getMessage());
        }
        return write(exchange, status);
    }

    Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status) {
        String traceId = exchange.getRequest().getId();
        ApiError error = switch (status.value()) {
            case 400 -> new ApiError("BAD_REQUEST", "请求参数无效", traceId);
            case 401 -> new ApiError("UNAUTHORIZED", "请使用有效的访问令牌", traceId);
            case 403 -> new ApiError("FORBIDDEN", "当前角色无权访问", traceId);
            case 404 -> new ApiError("NOT_FOUND", "接口不存在", traceId);
            case 405 -> new ApiError("METHOD_NOT_ALLOWED", "接口不支持此请求方法", traceId);
            case 503, 504 -> new ApiError("SERVICE_UNAVAILABLE", "服务暂不可用", traceId);
            default -> new ApiError("REQUEST_FAILED", "请求处理失败", traceId);
        };

        LOG.warn("gateway traceId={} status={} code={} path={}", traceId, status.value(), error.code(),
                exchange.getRequest().getPath().value());
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status.value() == 401) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(json.writeValueAsBytes(error))));
    }

    record ApiError(String code, String message, String traceId) {}
}
