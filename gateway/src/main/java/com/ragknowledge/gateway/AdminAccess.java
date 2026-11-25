package com.ragknowledge.gateway;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端「用户与权限」：通过 Keycloak Admin REST 读取租户内账号与平台角色。
 *
 * <p>网关以服务账号（admin-cli 密码授权，对应 Keycloak 26 的 KC_BOOTSTRAP_ADMIN_* 账户）访问
 * Admin REST，读取 realm 用户与其 realm-level 角色。只有携带 tenant_id 的用户才属于本平台，
 * 因此目录与计数都基于该过滤结果；Keycloak 不可用时返回 available=false 而不是让页面整页失败。
 */
@RestController
class AccessController {
    private final AccessDirectory directory;

    AccessController(AccessDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/api/v1/access")
    Mono<Map<String, Object>> directory() {
        return directory.snapshot();
    }
}

@Component
class AccessDirectory {
    private static final Logger LOG = LoggerFactory.getLogger(AccessDirectory.class);
    private static final Duration USER_CACHE_TTL = Duration.ofSeconds(30);
    private static final Set<String> PLATFORM_ROLES = Set.of("ADMIN", "EMPLOYEE");

    private final JsonMapper json;
    private final HttpClient client;
    private final String baseUrl;
    private final String realm;
    private final String username;
    private final String password;
    private final AtomicReference<String> token = new AtomicReference<>("");
    private final AtomicReference<Instant> tokenExpiry = new AtomicReference<>(Instant.EPOCH);
    private volatile List<Map<String, Object>> cachedUsers = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    AccessDirectory(JsonMapper json,
            @Value("${rag.keycloak-admin-url:http://keycloak:9000}") String baseUrl,
            @Value("${rag.keycloak-admin-realm:rag-local}") String realm,
            @Value("${rag.keycloak-admin-user:}") String username,
            @Value("${rag.keycloak-admin-password:}") String password) {
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.baseUrl = trim(baseUrl);
        this.realm = realm;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    Mono<Map<String, Object>> snapshot() {
        return Mono.fromCallable(this::snapshotSync).subscribeOn(Schedulers.boundedElastic());
    }

    /** 注册 / 角色变更后调用，让下次快照重新拉取 Keycloak。 */
    void invalidate() {
        cachedUsers = List.of();
        cachedAt = Instant.EPOCH;
    }

    private Map<String, Object> snapshotSync() {
        Map<String, Object> directory = new LinkedHashMap<>();
        List<Map<String, Object>> users;
        try {
            users = users();
            directory.put("available", true);
            directory.put("message", null);
        } catch (Exception failure) {
            LOG.warn("Unable to read accounts from Keycloak: {}", failure.toString());
            users = List.of();
            directory.put("available", false);
            directory.put("message", "身份服务暂不可用，请稍后重试。");
        }
        long admins = users.stream().filter(user -> roles(user).contains("ADMIN")).count();
        long employees = users.stream().filter(user -> roles(user).contains("EMPLOYEE")).count();
        List<String> tenants = users.stream()
                .map(user -> String.valueOf(user.get("tenantId")))
                .filter(tenant -> !tenant.isBlank())
                .distinct()
                .sorted()
                .toList();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", (long) users.size());
        counts.put("admins", admins);
        counts.put("employees", employees);
        counts.put("tenants", tenants);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("realm", realm);
        result.put("directory", directory);
        result.put("users", users);
        result.put("roles", List.of("ADMIN", "EMPLOYEE"));
        result.put("counts", counts);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> roles(Map<String, Object> user) {
        return user.get("roles") instanceof List<?> list ? (List<String>) list : List.of();
    }

    private List<Map<String, Object>> users() throws Exception {
        Instant now = Instant.now();
        if (now.isBefore(cachedAt.plus(USER_CACHE_TTL))) {
            return cachedUsers;
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<?, ?> raw : fetchList("/admin/realms/" + realm + "/users?max=200")) {
            String accountId = string(raw.get("id"));
            String tenantId = string(attribute(raw, "tenant_id"));
            if (tenantId.isBlank()) {
                // Keycloak hides unmanaged attributes from the admin API unless the realm
                // enables them, so fall back to the tenant group the realm import defines.
                tenantId = tenantFromGroups(accountId);
            }
            if (tenantId.isBlank()) {
                continue;
            }
            String accountName = string(raw.get("username"));
            List<String> accountRoles = new ArrayList<>();
            for (Map<?, ?> role : fetchList("/admin/realms/" + realm + "/users/" + accountId + "/role-mappings/realm")) {
                String name = string(role.get("name"));
                if (PLATFORM_ROLES.contains(name)) {
                    accountRoles.add(name);
                }
            }
            accountRoles.sort(Comparator.naturalOrder());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", accountName);
            row.put("email", string(raw.get("email")));
            row.put("enabled", raw.get("enabled") instanceof Boolean enabled ? enabled : Boolean.TRUE);
            row.put("tenantId", tenantId);
            row.put("roles", accountRoles);
            row.put("createdAt", createdAt(raw.get("createdTimestamp")));
            accounts.add(row);
        }
        accounts.sort(Comparator.comparing(row -> String.valueOf(row.get("username"))));
        cachedUsers = List.copyOf(accounts);
        cachedAt = Instant.now();
        return cachedUsers;
    }

    private String createdAt(Object epochMillis) {
        if (epochMillis instanceof Number number && number.longValue() > 0) {
            return Instant.ofEpochMilli(number.longValue()).toString();
        }
        return null;
    }

    private Object attribute(Map<?, ?> user, String key) {
        if (user.get("attributes") instanceof Map<?, ?> attributes && attributes.get(key) instanceof List<?> values) {
            return values.isEmpty() ? "" : values.get(0);
        }
        return "";
    }

    private String tenantFromGroups(String accountId) throws Exception {
        for (Map<?, ?> group : fetchList("/admin/realms/" + realm + "/users/" + accountId + "/groups")) {
            String name = string(group.get("name"));
            if (name.startsWith("tenant-")) {
                return name;
            }
        }
        return "";
    }

    private List<Map<?, ?>> fetchList(String path) throws Exception {
        HttpResponse<String> response = send(path);
        if (response.statusCode() == 401) {
            token.set("");
            response = send(path);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Keycloak responded with status " + response.statusCode());
        }
        Object parsed = json.readValue(response.body(), Object.class);
        List<Map<?, ?>> rows = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(map);
                }
            }
        }
        return rows;
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token())
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private synchronized String token() throws Exception {
        Instant now = Instant.now();
        if (now.isBefore(tokenExpiry.get()) && !token.get().isBlank()) {
            return token.get();
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Keycloak admin credentials are not configured");
        }
        String form = "grant_type=password"
                + "&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/realms/master/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak token endpoint responded with status " + response.statusCode());
        }
        Map<?, ?> payload = json.readValue(response.body(), Map.class);
        String accessToken = string(payload.get("access_token"));
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Keycloak token response did not include access_token");
        }
        long expiresIn = payload.get("expires_in") instanceof Number number ? number.longValue() : 60;
        token.set(accessToken);
        tokenExpiry.set(now.plusSeconds(Math.max(30, expiresIn - 30)));
        return accessToken;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String trim(String value) {
        String text = value == null ? "" : value.strip();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
