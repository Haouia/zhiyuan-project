package com.ragknowledge.gateway;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class GatewayRoutes {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayRoutes.class);

    @Bean
    RouteLocator documentsRoute(RouteLocatorBuilder builder,
            @Value("${rag.gateway-doc-token}") String documentToken,
            @Value("${rag.gateway-ai-token}") String answerToken) {
        Assert.hasText(documentToken, "RAG_GATEWAY_DOC_TOKEN is required");
        Assert.hasText(answerToken, "RAG_GATEWAY_AI_TOKEN is required");
        Assert.isTrue(!documentToken.equals(answerToken), "Internal service credentials must be distinct");
        GatewayFilter documentContext = verifiedContext(documentToken);
        return builder.routes()
                .route("document-reads", route -> route.path("/api/v1/documents", "/api/v1/documents/{id}/versions",
                                "/api/v1/documents/{id}/versions/{version}",
                                "/api/v1/documents/{id}/versions/{version}/source",
                                "/api/v1/documents/{id}/versions/{version}/parsed",
                                "/api/v1/documents/{id}/versions/{version}/file")
                        .and().method(HttpMethod.GET)
                        .filters(filters -> filters.rewritePath("/api/v1/(?<path>.*)", "/internal/v1/${path}")
                                .filter(documentContext))
                        .uri("lb://doc-service"))
                .route("document-uploads", route -> route.path("/api/v1/documents", "/api/v1/documents/{id}/versions")
                        .and().method(HttpMethod.POST)
                        .filters(filters -> filters.rewritePath("/api/v1/(?<path>.*)", "/internal/v1/${path}")
                                .filter(documentContext))
                        .uri("lb://doc-service"))
                .route("questions", route -> route.path("/api/v1/questions").and().method(HttpMethod.POST)
                        .filters(filters -> filters.setPath("/internal/v1/questions").filter(verifiedContext(answerToken)))
                        .metadata("response-timeout", 45_000)
                        .uri("lb://ai-service"))
                .route("usage", route -> route.path("/api/v1/usage").and().method(HttpMethod.GET)
                        .filters(filters -> filters.setPath("/internal/v1/usage").filter(verifiedContext(answerToken)))
                        .uri("lb://ai-service"))
                .build();
    }

    private GatewayFilter verifiedContext(String token) {
        return (exchange, chain) -> exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(auth -> {
                    String traceId = exchange.getRequest().getId();
                    String tenantId = GatewaySecurity.tenantIdOf(auth.getToken());
                    LOG.info("gateway traceId={} tenant={} path={}", traceId,
                            tenantId, exchange.getRequest().getPath().value());
                    return chain.filter(exchange.mutate()
                            .request(request -> request.headers(headers -> {
                                headers.setBearerAuth(token);
                                headers.set("X-Tenant-Id", tenantId);
                                headers.set("X-Trace-Id", traceId);
                            })).build());
                });
    }
}

@RestController
class IdentityController {
    @GetMapping("/api/v1/identity")
    Map<String, Object> identity(JwtAuthenticationToken auth) {
        return Map.of(
                "subject", auth.getToken().getSubject(),
                "tenantId", GatewaySecurity.tenantIdOf(auth.getToken()),
                "roles", auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(role -> role.substring("ROLE_".length()))
                        .sorted()
                        .toList());
    }
}
