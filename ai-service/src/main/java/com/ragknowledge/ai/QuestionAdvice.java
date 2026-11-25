package com.ragknowledge.ai;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 媒体类型不匹配在 handler 选择阶段抛出，控制器内的 @ExceptionHandler 不会生效，
 * 会落到 Spring 默认的 /error 响应。这里统一补上 code、message、traceId 错误信封。
 */
@RestControllerAdvice
class QuestionAdvice {
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> unsupported(HttpServletRequest request) {
        String header = request.getHeader("X-Trace-Id");
        String traceId = header != null && header.matches("[A-Za-z0-9_-]{1,64}")
                ? header : UUID.randomUUID().toString();
        return ResponseEntity.status(415).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "UNSUPPORTED_MEDIA_TYPE", "message", "请使用 application/json 提交问题",
                        "traceId", traceId));
    }
}
