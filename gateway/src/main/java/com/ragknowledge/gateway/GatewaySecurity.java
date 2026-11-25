package com.ragknowledge.gateway;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class GatewaySecurity {
    private static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Set<String> ROLES = Set.of("ADMIN", "EMPLOYEE");

    @Bean
    SecurityWebFilterChain apiSecurity(ServerHttpSecurity http, ApiErrors errors) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.GET, "/", "/admin", "/admin/", "/ask", "/ask/",
                                "/register", "/register/",
                                "/admin/config.json", "/admin/assets/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/register").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/questions").hasAnyRole("ADMIN", "EMPLOYEE")
                        .pathMatchers(HttpMethod.GET, "/api/v1/usage").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/documents/{id}/versions/{version}/source").hasAnyRole("ADMIN", "EMPLOYEE")
                        .pathMatchers(HttpMethod.GET, "/api/v1/documents/{id}/versions/{version}/parsed",
                                "/api/v1/documents/{id}/versions/{version}/file").hasRole("ADMIN")
                        .pathMatchers("/api/v1/documents", "/api/v1/documents/{id}/versions",
                                "/api/v1/documents/{id}/versions/{version}").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/identity").hasAnyRole("ADMIN", "EMPLOYEE")
                        .pathMatchers(HttpMethod.GET, "/api/v1/evals", "/api/v1/evals/{reportId}",
                                "/api/v1/monitoring", "/api/v1/access").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/evals").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/invite-codes").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/invite-codes/rotate", "/api/v1/users/**").hasRole("ADMIN")
                        .anyExchange().denyAll())
                .exceptionHandling(auth -> auth
                        .authenticationEntryPoint((exchange, failure) -> errors.write(exchange, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, failure) -> errors.write(exchange, HttpStatus.FORBIDDEN)))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint((exchange, failure) -> errors.write(exchange, HttpStatus.UNAUTHORIZED))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(this::authenticate)))
                .build();
    }

    private Mono<JwtAuthenticationToken> authenticate(Jwt jwt) {
        String tenantId = tenantIdOf(jwt);
        if (tenantId == null
                || !StringUtils.hasText(jwt.getSubject()) || jwt.getExpiresAt() == null) {
            return Mono.error(new OAuth2AuthenticationException("invalid_token"));
        }

        Object roles = jwt.getClaims().get("roles");
        List<SimpleGrantedAuthority> authorities = roles instanceof Collection<?> values
                ? values.stream()
                        .filter(String.class::isInstance)
                        .filter(ROLES::contains)
                        .distinct()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList()
                : List.of();
        return Mono.just(new JwtAuthenticationToken(jwt, authorities));
    }

    /** tenant_id 声明：Keycloak 分组映射会输出数组，用户属性映射输出字符串，这里统一取第一个合法值。 */
    static String tenantIdOf(Jwt jwt) {
        Object value = jwt.getClaims().get("tenant_id");
        if (value instanceof String tenant && TENANT_ID.matcher(tenant).matches()) {
            return tenant;
        }
        if (value instanceof Collection<?> tenants) {
            return tenants.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(TENANT_ID.asPredicate())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
