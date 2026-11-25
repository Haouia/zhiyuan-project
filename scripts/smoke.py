import base64
import hashlib
import json
import secrets
import subprocess
from html.parser import HTMLParser
from http.cookiejar import CookieJar, DefaultCookiePolicy
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPCookieProcessor, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
GATEWAY = "http://127.0.0.1:8080"
ISSUER = "http://127.0.0.1:8180/realms/rag-local"
CALLBACK = "http://localhost:8080/oidc/callback"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoginForm(HTMLParser):
    action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def compose(*args, sql=None, check=True):
    result = subprocess.run(
        ["docker", "compose", *args], cwd=ROOT, input=sql, capture_output=True,
        encoding="utf-8", errors="replace", timeout=60,
    )
    if check and result.returncode:
        raise RuntimeError(f"docker compose {args[0]} failed (exit {result.returncode}): {result.stderr.strip()}")
    return result


def request(opener, url, *, form=None, headers=None, method=None):
    data = urlencode(form).encode() if form is not None else None
    try:
        response = opener.open(Request(url, data, headers or {}, method=method), timeout=15)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def login(metadata, username):
    # Keycloak and browsers treat loopback HTTP as a secure context for this login.
    cookies = CookieJar(DefaultCookiePolicy(secure_protocols=("http", "https")))
    opener = build_opener(NoRedirect(), HTTPCookieProcessor(cookies), ProxyHandler({}))
    verifier = secrets.token_urlsafe(32)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(24)
    parameters = {
        "client_id": "rag-web", "redirect_uri": CALLBACK, "response_type": "code", "scope": "openid",
        "state": state, "nonce": secrets.token_urlsafe(24), "code_challenge": challenge, "code_challenge_method": "S256",
    }
    authorization_url = metadata["authorization_endpoint"] + "?" + urlencode(parameters)
    status, _, body = request(opener, authorization_url)
    parser = LoginForm()
    parser.feed(body.decode("utf-8"))
    require(status == 200 and parser.action, f"Keycloak login form unavailable for {username} (HTTP {status})")
    action = urljoin(authorization_url, parser.action)
    require(action.startswith(ISSUER + "/"), "Unexpected Keycloak form target")
    status, headers, _ = request(opener, action, form={"username": username, "password": "RagLocalExample42!"})
    require(status == 302 and headers.get("Location"), f"Keycloak rejected login for {username} (HTTP {status})")
    callback = urlsplit(headers["Location"])
    parameters = parse_qs(callback.query)
    require(callback._replace(query="").geturl() == CALLBACK and parameters.get("state") == [state] and parameters.get("code"),
            f"Invalid OIDC callback for {username}")
    status, _, body = request(opener, metadata["token_endpoint"], form={
        "grant_type": "authorization_code", "client_id": "rag-web", "redirect_uri": CALLBACK,
        "code": parameters["code"][0], "code_verifier": verifier,
    })
    require(status == 200, f"Code exchange failed for {username} (HTTP {status})")
    return json.loads(body)


def api(path, status, token=None, extra_headers=None, method=None):
    headers = dict(extra_headers or {})
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    actual, _, body = request(build_opener(NoRedirect(), ProxyHandler({})), GATEWAY + path, headers=headers, method=method)
    require(actual == status, f"{method or 'GET'} {path}: expected HTTP {status}, received {actual}")
    payload = json.loads(body)
    if status >= 400:
        require(all(payload.get(field) for field in ("code", "message", "traceId")), f"Incomplete error response for {path}")
    return payload


def mysql(statement, user="root", check=True):
    password_variable = {"root": "MYSQL_ROOT_PASSWORD", "rag_doc": "RAG_DOC_DB_PASSWORD", "rag_search": "RAG_SEARCH_DB_PASSWORD"}[user]
    return compose(
        "exec", "-T", "mysql", "bash", "-c",
        f'MYSQL_PWD="${password_variable}" exec mysql --user={user} --default-character-set=utf8mb4 --batch --skip-column-names',
        sql=statement, check=check,
    )


def check_discovery_and_migrations():
    require(api("/actuator/health", 200)["status"] == "UP", "Gateway health check failed")
    services = {"doc-service": 8082, "search-service": 8083, "ai-service": 8081}
    for service, port in services.items():
        probe = compose("exec", "-T", service, "curl", "-fsS", f"http://127.0.0.1:{port}/actuator/health")
        health = json.loads(probe.stdout)
        discovered = health["components"]["discoveryComposite"]["components"]["discoveryClient"]["details"]["services"]
        require(health["status"] == "UP" and (services.keys() | {"gateway"}) <= set(discovered)
                and "metric-service" not in discovered, f"Unexpected Nacos discovery results in {service}")
    for schema in ("doc_db", "search_db"):
        version = mysql(f"SELECT version FROM {schema}.flyway_schema_history WHERE success = 1 AND version = '1';")
        require(version.stdout.strip() == "1", f"Flyway V1 missing from {schema}")
    for user, other_table in (("rag_doc", "search_db.doc_chunk"), ("rag_search", "doc_db.kb_document")):
        result = mysql(f"SELECT COUNT(*) FROM {other_table};", user=user, check=False)
        require(result.returncode != 0 and "denied" in result.stderr, f"{user} can access the other service's schema")
    print("PASS: Nacos discovery of gateway and three Java services, Flyway V1, database grants", flush=True)


def check_oidc_and_lists():
    status, _, body = request(build_opener(NoRedirect(), ProxyHandler({})), ISSUER + "/.well-known/openid-configuration")
    require(status == 200, "Local OIDC discovery unavailable")
    metadata = json.loads(body)
    require(metadata["issuer"] == ISSUER and all(metadata[key].startswith(ISSUER + "/")
            for key in ("authorization_endpoint", "token_endpoint")), "Unexpected OIDC provider metadata")

    accounts = {
        "a-admin": ("tenant-a", "ADMIN"), "a-employee": ("tenant-a", "EMPLOYEE"),
        "b-admin": ("tenant-b", "ADMIN"), "b-employee": ("tenant-b", "EMPLOYEE"),
    }
    tokens = {user: login(metadata, user) for user in [*accounts, "no-role", "no-tenant"]}
    for user, (tenant, role) in accounts.items():
        access_token = tokens[user]["access_token"]
        identity = api("/api/v1/identity", 200, access_token)
        require(identity["tenantId"] == tenant and identity["roles"] == [role] and identity["subject"], f"Incorrect identity for {user}")
        if role == "EMPLOYEE":
            api("/api/v1/documents", 403, access_token, {"X-Roles": "ADMIN"})
            api("/api/v1/documents", 403, access_token, method="POST")

    api("/api/v1/identity", 401)
    api("/api/v1/identity", 401, tokens["a-admin"]["id_token"])
    api("/api/v1/identity", 401, tokens["no-tenant"]["access_token"])
    api("/api/v1/identity", 403, tokens["no-role"]["access_token"])
    a_token = tokens["a-admin"]["access_token"]
    signed_part, signature = a_token.rsplit(".", 1)
    bad_token = signed_part + "." + ("B" if signature[0] == "A" else "A") + signature[1:]
    api("/api/v1/identity", 401, bad_token)
    api("/internal/v1/documents", 403, a_token)

    marker = "Phase1-" + secrets.token_hex(16)
    rows = [("tenant-a", marker + "-a-年假政策"), ("tenant-a", marker + "-a-考勤政策"), ("tenant-b", marker + "-b-年假政策")]
    titles = ",".join(f"'{title}'" for _, title in rows)
    try:
        statements = ["START TRANSACTION;"]
        for tenant, title in rows:
            statements.extend([
                f"INSERT INTO doc_db.kb_document(tenant_id,title,latest_version) VALUES ('{tenant}','{title}',1);",
                "INSERT INTO doc_db.kb_document_version(document_id,version,filename,content_type,original_bytes,status,error_code) "
                "VALUES (LAST_INSERT_ID(),1,'phase1.txt','text/plain',0x61,'FAILED','NO_EXTRACTABLE_TEXT');",
            ])
        mysql("\n".join([*statements, "COMMIT;"]))

        for user, (tenant, forged) in {"a-admin": ("tenant-a", "tenant-b"), "b-admin": ("tenant-b", "tenant-a")}.items():
            token = tokens[user]["access_token"]
            page = api("/api/v1/documents?size=100", 200, token, {"X-Tenant-Id": forged})
            documents = [item for item in page["items"] if item["title"].startswith(marker)]
            require({item["title"] for item in documents} == {title for key, title in rows if key == tenant},
                    f"Tenant filter failed for {user} with a forged tenant header")
            require(all(item["latestVersion"] == 1 and item["activeVersion"] is None for item in documents),
                    "Unpublished document fixture appeared as active")

        first = api("/api/v1/documents?page=0&size=1", 200, a_token)
        second = api("/api/v1/documents?page=1&size=1", 200, a_token)
        require(first["hasNext"] and first["items"][0]["documentId"] != second["items"][0]["documentId"], "Pagination failed")
        api("/api/v1/documents?size=101", 400, a_token)
        api("/api/v1/documents?page=invalid", 400, a_token)
    finally:
        mysql(f"START TRANSACTION; DELETE FROM doc_db.kb_document_version WHERE document_id IN "
              f"(SELECT id FROM doc_db.kb_document WHERE title IN ({titles})); "
              f"DELETE FROM doc_db.kb_document WHERE title IN ({titles}); COMMIT;")
        remaining = mysql(f"SELECT COUNT(*) FROM doc_db.kb_document WHERE title IN ({titles});")
        require(remaining.stdout.strip() == "0", "Smoke test document cleanup was incomplete")

    direct = compose("exec", "-T", "gateway", "curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                     "-H", "X-Tenant-Id: tenant-a", "http://doc-service:8082/internal/v1/documents")
    require(direct.stdout.strip() == "401", "doc-service accepted a request without service credentials")
    print("PASS: OIDC code + PKCE, JWT and roles, real database tenant isolation and Nacos gateway routing", flush=True)


def check_metric():
    result = compose("run", "--rm", "--no-deps", "metric-probe")
    print(result.stdout.strip(), flush=True)
    rejected = compose("run", "--rm", "--no-deps", "-e", "RAG_METRIC_TOKEN=invalid-fixture", "metric-probe", check=False)
    require(rejected.returncode != 0 and "HTTP 401" in rejected.stderr, "metric-service accepted an invalid service credential")
    print("PASS: Java reached the independent Python service and invalid service credentials were rejected", flush=True)


if __name__ == "__main__":
    check_discovery_and_migrations()
    check_oidc_and_lists()
    check_metric()
