package com.ragknowledge.gateway;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * 自助注册与账号治理：
 * <ul>
 *   <li>POST /api/v1/register —— 匿名接口，凭管理员发放的邀请码注册为普通成员（EMPLOYEE）；</li>
 *   <li>GET/POST /api/v1/invite-codes —— 管理员查看/立即轮换本租户邀请码（每两小时自动轮换）；</li>
 *   <li>POST /api/v1/users/{username}/promote|demote —— 管理员擢升/降级本租户账号。</li>
 * </ul>
 *
 * <p>所有 Keycloak 操作走 Admin REST（KC_BOOTSTRAP_ADMIN_* 服务账号），与
 * {@link AccessDirectory} 相同；注册成功后同时写入用户属性与租户分组，目录接口
 * 对隐藏属性有分组回退，因此新账号可以立即出现在「用户与权限」中。
 */
@RestController
class RegisterController {
    private final KeycloakAdmin keycloak;
    private final InviteCodes inviteCodes;
    private final AccessDirectory directory;

    RegisterController(KeycloakAdmin keycloak, InviteCodes inviteCodes, AccessDirectory directory) {
        this.keycloak = keycloak;
        this.inviteCodes = inviteCodes;
        this.directory = directory;
    }

    @PostMapping(value = "/api/v1/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Map<String, Object>>> register(@RequestBody(required = false) Map<String, Object> payload,
            ServerWebExchange exchange) {
        String traceId = exchange.getRequest().getId();
        // 注册仅限匿名访问：已登录用户（含 EMPLOYEE）一律拒绝，避免误用。
        return exchange.getPrincipal()
                .map(principal -> failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "注册接口仅限匿名访问", traceId))
                .switchIfEmpty(Mono.fromCallable(() -> registerSync(payload, traceId))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private ResponseEntity<Map<String, Object>> registerSync(Map<String, Object> payload, String traceId) {
        String username = string(payload == null ? null : payload.get("username")).strip();
        String password = string(payload == null ? null : payload.get("password"));
        String inviteCode = string(payload == null ? null : payload.get("inviteCode")).strip().toUpperCase();

        if (!USERNAME.matcher(username).matches()) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "用户名需为 3–32 位字母、数字、点、下划线或连字符", traceId);
        }
        if (password.length() < 8 || password.length() > 128) {
            return failure(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "密码长度需为 8–128 位", traceId);
        }
        if (!INVITE_CODE.matcher(inviteCode).matches()) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE", "邀请码格式无效", traceId);
        }
        InviteCodes.Match match = inviteCodes.tenantFor(inviteCode);
        if (match == null) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE", "邀请码无效，请联系管理员获取", traceId);
        }
        if (match.expired()) {
            return failure(HttpStatus.BAD_REQUEST, "INVITE_CODE_EXPIRED", "邀请码已过期，请联系管理员获取新邀请码", traceId);
        }
        try {
            keycloak.createAccount(username, password, match.tenant());
        } catch (KeycloakAdmin.AdminException failure) {
            if (failure.status() == HttpStatus.CONFLICT.value()) {
                return failure(HttpStatus.CONFLICT, "USERNAME_TAKEN", "该用户名已被注册，请更换后重试", traceId);
            }
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "REGISTRATION_FAILED", "注册失败，身份服务暂不可用，请稍后重试", traceId);
        } catch (Exception failure) {
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "REGISTRATION_FAILED", "注册失败，请稍后重试", traceId);
        }
        directory.invalidate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("tenantId", match.tenant());
        result.put("message", "注册成功，请登录");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String code, String message, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("traceId", traceId);
        return ResponseEntity.status(status).body(body);
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    private static final Pattern INVITE_CODE = Pattern.compile("[A-Z0-9]{8}");
}

@RestController
class InviteCodesController {
    private final InviteCodes inviteCodes;

    InviteCodesController(InviteCodes inviteCodes) {
        this.inviteCodes = inviteCodes;
    }

    @GetMapping("/api/v1/invite-codes")
    Mono<Map<String, Object>> list(JwtAuthenticationToken auth) {
        return Mono.fromCallable(() -> inviteCodes.response(tenantOf(auth))).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api/v1/invite-codes/rotate")
    Mono<Map<String, Object>> rotate(JwtAuthenticationToken auth) {
        return Mono.fromCallable(() -> inviteCodes.rotate(tenantOf(auth))).subscribeOn(Schedulers.boundedElastic());
    }

    static String tenantOf(JwtAuthenticationToken auth) {
        return GatewaySecurity.tenantIdOf(auth.getToken());
    }
}

@RestController
class UserAdminController {
    private final KeycloakAdmin keycloak;
    private final AccessDirectory directory;

    UserAdminController(KeycloakAdmin keycloak, AccessDirectory directory) {
        this.keycloak = keycloak;
        this.directory = directory;
    }

    @PostMapping("/api/v1/users/{username}/promote")
    Mono<ResponseEntity<Map<String, Object>>> promote(@PathVariable String username,
            JwtAuthenticationToken auth, ServerWebExchange exchange) {
        return change(username, auth, exchange, "ADMIN", "EMPLOYEE");
    }

    @PostMapping("/api/v1/users/{username}/demote")
    Mono<ResponseEntity<Map<String, Object>>> demote(@PathVariable String username,
            JwtAuthenticationToken auth, ServerWebExchange exchange) {
        return change(username, auth, exchange, "EMPLOYEE", "ADMIN");
    }

    private Mono<ResponseEntity<Map<String, Object>>> change(String username, JwtAuthenticationToken auth,
            ServerWebExchange exchange, String add, String remove) {
        String traceId = exchange.getRequest().getId();
        String tenant = InviteCodesController.tenantOf(auth);
        String caller = auth.getToken().getClaimAsString("preferred_username");
        return Mono.fromCallable(() -> changeSync(username, tenant, caller == null ? "" : caller, add, remove, traceId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Map<String, Object>> changeSync(String username, String tenant, String caller,
            String add, String remove, String traceId) {
        if (username == null || username.isBlank() || !RegisterController.USERNAME.matcher(username).matches()) {
            return RegisterController.failure(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "用户名格式无效", traceId);
        }
        try {
            List<String> roles = keycloak.changeRoles(username, tenant, caller, add, remove);
            directory.invalidate();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("username", username);
            result.put("roles", roles);
            return ResponseEntity.ok(result);
        } catch (KeycloakAdmin.AdminFlowException failure) {
            return RegisterController.failure(failure.status(), failure.code(), failure.message(), traceId);
        } catch (Exception failure) {
            return RegisterController.failure(HttpStatus.INTERNAL_SERVER_ERROR, "REQUEST_FAILED",
                    "身份服务暂不可用，请稍后重试", traceId);
        }
    }
}

/**
 * Keycloak Admin REST 客户端：账号创建、角色映射与分组。独立于 {@link AccessDirectory}
 * 维护自己的 admin-cli 令牌，互不干扰。
 */
@Component
class KeycloakAdmin {
    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAdmin.class);

    private final JsonMapper json;
    private final HttpClient client;
    private final String baseUrl;
    private final String realm;
    private final String username;
    private final String password;
    private final AtomicReference<String> token = new AtomicReference<>("");
    private final AtomicReference<Instant> tokenExpiry = new AtomicReference<>(Instant.EPOCH);

    KeycloakAdmin(JsonMapper json,
            @Value("${rag.keycloak-admin-url:http://keycloak:8080}") String baseUrl,
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

    /** 创建普通成员：用户 + 初始密码 + 租户分组 + EMPLOYEE 角色。 */
    void createAccount(String username, String password, String tenant) throws Exception {
        // email/firstName/lastName 均为 user-profile 必填：缺少时首次登录会被强制 VERIFY_PROFILE 重定向。
        String body = json.writeValueAsString(Map.of(
                "username", username,
                "enabled", true,
                "emailVerified", false,
                "email", username + "@example.invalid",
                "firstName", username,
                "lastName", "用户",
                "attributes", Map.of("tenant_id", List.of(tenant))));
        HttpResponse<String> created = send("POST", "/admin/realms/" + realm + "/users", body, 201);
        String userId = created.headers().firstValue("Location")
                .map(location -> location.substring(location.lastIndexOf('/') + 1))
                .orElse("");
        if (userId.isBlank()) {
            userId = findUserId(username);
        }
        send("PUT", "/admin/realms/" + realm + "/users/" + userId + "/reset-password",
                json.writeValueAsString(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false)),
                204);
        joinTenantGroup(userId, tenant);
        addRole(userId, "EMPLOYEE");
        LOG.info("Registered account {} into tenant {}", username, tenant);
    }

    /** 变更角色：add 添加、remove 移除；校验租户归属与禁止自改。返回变更后的角色列表。 */
    List<String> changeRoles(String username, String callerTenant, String callerUsername, String add, String remove)
            throws Exception {
        String userId = findUserId(username);
        if (userId.isBlank()) {
            throw new AdminFlowException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "未找到该用户");
        }
        String tenant = tenantOf(userId);
        if (tenant.isBlank() || !tenant.equals(callerTenant)) {
            throw new AdminFlowException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅能管理本租户用户");
        }
        if (username.equals(callerUsername)) {
            throw new AdminFlowException(HttpStatus.BAD_REQUEST, "CANNOT_MODIFY_SELF", "不能修改自己的角色");
        }
        addRole(userId, add);
        removeRole(userId, remove);
        List<String> roles = roleNames(userId);
        roles.sort(Comparator.naturalOrder());
        LOG.info("Changed roles of {} (tenant {}): +{} -{} -> {}", username, callerTenant, add, remove, roles);
        return roles;
    }

    private void joinTenantGroup(String userId, String tenant) throws Exception {
        String groupId = groupId(tenant);
        if (!groupId.isBlank()) {
            send("PUT", "/admin/realms/" + realm + "/users/" + userId + "/groups/" + groupId, null, 204);
        }
    }

    private void addRole(String userId, String role) throws Exception {
        // Keycloak 26 要求 role-mapping 载荷包含角色 id，仅 name 会返回 404 Role not found。
        send("POST", "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm",
                json.writeValueAsString(List.of(roleRef(role))), 204);
    }

    private void removeRole(String userId, String role) throws Exception {
        send("DELETE", "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm",
                json.writeValueAsString(List.of(roleRef(role))), 204);
    }

    /** 组装 {id,name} 角色引用：通过 GET /roles/{role} 解析角色 id。 */
    private Map<String, String> roleRef(String role) throws Exception {
        String id = string(fetchObject("/admin/realms/" + realm + "/roles/" + role).get("id"));
        if (id.isBlank()) {
            throw new AdminException(HttpStatus.NOT_FOUND.value());
        }
        return Map.of("id", id, "name", role);
    }

    private List<String> roleNames(String userId) throws Exception {
        List<String> roles = new ArrayList<>();
        for (Map<?, ?> role : fetchList("/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm")) {
            String name = string(role.get("name"));
            if ("ADMIN".equals(name) || "EMPLOYEE".equals(name)) {
                roles.add(name);
            }
        }
        return roles;
    }

    private String groupId(String name) throws Exception {
        for (Map<?, ?> group : fetchList("/admin/realms/" + realm + "/groups?search="
                + URLEncoder.encode(name, StandardCharsets.UTF_8))) {
            if (name.equals(string(group.get("name")))) {
                return string(group.get("id"));
            }
        }
        return "";
    }

    private String findUserId(String username) throws Exception {
        for (Map<?, ?> user : fetchList("/admin/realms/" + realm + "/users?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&exact=true")) {
            return string(user.get("id"));
        }
        return "";
    }

    /** 用户租户：优先属性 tenant_id，隐藏时回退到租户分组（与 AccessDirectory 一致）。 */
    private String tenantOf(String userId) throws Exception {
        Map<?, ?> user = fetchObject("/admin/realms/" + realm + "/users/" + userId);
        String tenant = attribute(user, "tenant_id");
        if (!tenant.isBlank()) {
            return tenant;
        }
        for (Map<?, ?> group : fetchList("/admin/realms/" + realm + "/users/" + userId + "/groups")) {
            String name = string(group.get("name"));
            if (name.startsWith("tenant-")) {
                return name;
            }
        }
        return "";
    }

    private String attribute(Map<?, ?> user, String key) {
        if (user.get("attributes") instanceof Map<?, ?> attributes && attributes.get(key) instanceof List<?> values) {
            return values.isEmpty() ? "" : string(values.get(0));
        }
        return "";
    }

    private List<Map<?, ?>> fetchList(String path) throws Exception {
        Object parsed = json.readValue(send("GET", path, null, 200).body(), Object.class);
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

    private Map<?, ?> fetchObject(String path) throws Exception {
        Object parsed = json.readValue(send("GET", path, null, 200).body(), Object.class);
        return parsed instanceof Map<?, ?> map ? map : Map.of();
    }

    private HttpResponse<String> send(String method, String path, String body, int expected) throws Exception {
        HttpResponse<String> response = request(method, path, body);
        if (response.statusCode() == 401 && !path.contains("/protocol/openid-connect/token")) {
            token.set("");
            response = request(method, path, body);
        }
        if (response.statusCode() != expected) {
            throw new AdminException(response.statusCode());
        }
        return response;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + adminToken());
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private synchronized String adminToken() throws Exception {
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

    /** Keycloak 返回了非预期状态码。 */
    static class AdminException extends RuntimeException {
        private final int status;

        AdminException(int status) {
            super("Keycloak responded with status " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** 业务流校验失败（租户不符 / 目标不存在 / 自改角色），由控制器映射为显式错误信封。 */
    static class AdminFlowException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        private final String message;

        AdminFlowException(HttpStatus status, String code, String message) {
            super(code + ": " + message);
            this.status = status;
            this.code = code;
            this.message = message;
        }

        HttpStatus status() {
            return status;
        }

        String code() {
            return code;
        }

        String message() {
            return message;
        }
    }
}

/**
 * 每租户单枚活跃邀请码，两小时自动轮换；文件持久化，历史保留 10 条。
 */
@Component
class InviteCodes {
    private static final Logger LOG = LoggerFactory.getLogger(InviteCodes.class);
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final long ROTATION_SECONDS = 2 * 60 * 60;
    private static final int HISTORY_MAX = 10;

    private final JsonMapper json;
    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "invite-rotation");
                thread.setDaemon(true);
                return thread;
            });
    private final Object lock = new Object();
    private Map<String, TenantState> tenants = null;

    InviteCodes(JsonMapper json, @Value("${rag.invite-dir:/app/data}") String inviteDir) {
        this.json = json;
        this.file = Path.of(inviteDir).resolve("invite-codes.json");
        scheduler.scheduleWithFixedDelay(this::rotateExpired, 30, 30, TimeUnit.SECONDS);
    }

    record Match(String tenant, boolean expired) {
    }

    /** 校验邀请码：返回所属租户；已过期返回 expired=true；未知返回 null。 */
    Match tenantFor(String code) {
        synchronized (lock) {
            load();
            for (Map.Entry<String, TenantState> entry : tenants.entrySet()) {
                if (code.equals(entry.getValue().code)) {
                    return new Match(entry.getKey(), Instant.now().toEpochMilli() >= entry.getValue().expiresAt);
                }
            }
            return null;
        }
    }

    /** 本租户邀请码视图（缺失或已过期时自动轮换出新的）。 */
    Map<String, Object> response(String tenant) {
        synchronized (lock) {
            load();
            ensureFresh(tenant);
            return snapshot(tenant);
        }
    }

    /** 强制轮换并返回新视图。 */
    Map<String, Object> rotate(String tenant) {
        synchronized (lock) {
            load();
            rotateInternal(tenant);
            persist();
            return snapshot(tenant);
        }
    }

    private Map<String, Object> snapshot(String tenant) {
        TenantState state = tenants.get(tenant);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rotationMinutes", ROTATION_SECONDS / 60);
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("code", state.code);
        active.put("tenantId", tenant);
        active.put("generatedAt", state.generatedAt);
        active.put("expiresAt", state.expiresAt);
        active.put("remainingSeconds", Math.max(0, (state.expiresAt - Instant.now().toEpochMilli()) / 1000));
        result.put("active", active);
        result.put("history", state.history);
        return result;
    }

    private void ensureFresh(String tenant) {
        TenantState state = tenants.get(tenant);
        if (state == null || Instant.now().toEpochMilli() >= state.expiresAt) {
            rotateInternal(tenant);
            persist();
        }
    }

    private void rotateInternal(String tenant) {
        TenantState state = tenants.get(tenant);
        if (state == null) {
            state = new TenantState();
            tenants.put(tenant, state);
        } else if (state.code != null) {
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put("code", state.code);
            previous.put("generatedAt", state.generatedAt);
            previous.put("expiresAt", state.expiresAt);
            state.history.add(0, previous);
            while (state.history.size() > HISTORY_MAX) {
                state.history.remove(state.history.size() - 1);
            }
        }
        state.code = nextCode(tenant);
        state.generatedAt = Instant.now().toEpochMilli();
        state.expiresAt = state.generatedAt + ROTATION_SECONDS * 1000;
        LOG.info("Rotated invite code for tenant {}", tenant);
    }

    private String nextCode(String tenant) {
        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int index = 0; index < CODE_LENGTH; index++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String candidate = code.toString();
            TenantState state = tenants.get(tenant);
            boolean collision = state != null && (candidate.equals(state.code)
                    || state.history.stream().anyMatch(item -> candidate.equals(item.get("code"))));
            if (!collision) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique invite code");
    }

    private void rotateExpired() {
        try {
            synchronized (lock) {
                load();
                boolean changed = false;
                long now = Instant.now().toEpochMilli();
                for (TenantState state : tenants.values()) {
                    if (state.code != null && now >= state.expiresAt) {
                        rotateInternal(stateTenant(state));
                        changed = true;
                    }
                }
                if (changed) {
                    persist();
                }
            }
        } catch (Exception failure) {
            LOG.warn("Unable to rotate expired invite codes: {}", failure.toString());
        }
    }

    private String stateTenant(TenantState state) {
        for (Map.Entry<String, TenantState> entry : tenants.entrySet()) {
            if (entry.getValue() == state) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Tenant state is not tracked");
    }

    private void load() {
        if (tenants != null) {
            return;
        }
        Map<String, TenantState> loaded = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file)) {
                Object parsed = json.readValue(Files.readString(file, StandardCharsets.UTF_8), Object.class);
                if (parsed instanceof Map<?, ?> root && root.get("tenants") instanceof Map<?, ?> raw) {
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> data)) {
                            continue;
                        }
                        TenantState state = new TenantState();
                        state.code = string(data.get("code"));
                        state.generatedAt = number(data.get("generatedAt"));
                        state.expiresAt = number(data.get("expiresAt"));
                        if (data.get("history") instanceof List<?> history) {
                            for (Object item : history) {
                                if (item instanceof Map<?, ?> row) {
                                    Map<String, Object> copy = new LinkedHashMap<>();
                                    row.forEach((key, value) -> copy.put(String.valueOf(key), value));
                                    state.history.add(copy);
                                }
                            }
                        }
                        loaded.put(name, state);
                    }
                }
            }
        } catch (Exception failure) {
            LOG.warn("Unable to read invite codes from {}: {}", file, failure.toString());
        }
        tenants = loaded;
    }

    private void persist() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            Map<String, Object> raw = new LinkedHashMap<>();
            for (Map.Entry<String, TenantState> entry : tenants.entrySet()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("code", entry.getValue().code);
                data.put("generatedAt", entry.getValue().generatedAt);
                data.put("expiresAt", entry.getValue().expiresAt);
                data.put("history", entry.getValue().history);
                raw.put(entry.getKey(), data);
            }
            root.put("tenants", raw);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            LOG.error("Unable to persist invite codes to {}: {}", file, failure.toString());
        }
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static final class TenantState {
        private String code;
        private long generatedAt;
        private long expiresAt;
        private final List<Map<String, Object>> history = new ArrayList<>();
    }
}
