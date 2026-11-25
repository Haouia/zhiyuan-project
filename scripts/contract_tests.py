"""契约测试：核对 interfaces.json 与网关/服务实现，并对运行中的服务逐条验契约。

在 docker compose up --wait 之后运行；不写入业务数据，可重复执行。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

sys.path.insert(0, str(Path(__file__).resolve().parent))
from smoke import GATEWAY, ISSUER, NoRedirect, api, compose, login, request, require  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = json.loads((ROOT / "contracts" / "interfaces.json").read_text(encoding="utf-8"))
PROVIDER_FILES = {
    "gateway": ROOT / "gateway/src/main/java/com/ragknowledge/gateway",
    "doc-service": ROOT / "doc-service/src/main/java/com/ragknowledge/doc",
    "search-service": ROOT / "search-service/src/main/java/com/ragknowledge/search",
    "ai-service": ROOT / "ai-service/src/main/java/com/ragknowledge/ai",
    "metric-service": ROOT / "metric-service",
    "keycloak": ROOT / "docker/keycloak",
}


def http(url, data=None, headers=None, method=None, timeout=20):
    opener = build_opener(NoRedirect(), ProxyHandler({}))
    try:
        response = opener.open(Request(url, data, headers or {}, method=method), timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.read()


def envelope(raw, label):
    payload = json.loads(raw)
    for field in CONTRACT["errorEnvelope"]:
        require(payload.get(field), f"{label}: error response misses {field}")
    return payload


def check_static_contract():
    gateway_sources = "\n".join(item.read_text(encoding="utf-8")
                                for item in (ROOT / "gateway/src/main/java/com/ragknowledge/gateway").glob("*.java"))
    for interface in CONTRACT["external"]:
        require(interface["path"] in gateway_sources,
                f"gateway routes do not declare {interface['path']} ({interface['id']})")
    for interface in CONTRACT["internal"]:
        directory = PROVIDER_FILES[interface["provider"]]
        sources = "\n".join(item.read_text(encoding="utf-8")
                            for pattern in ("*.java", "*.py", "*.json")
                            for item in directory.glob(pattern) if item.is_file())
        needle = [part for part in interface["path"].split("/") if part and not part.startswith("{")][-1]
        require(needle in sources, f"{interface['provider']} does not implement {interface['path']} ({interface['id']})")
    print(f"PASS: {len(CONTRACT['external'])} external + {len(CONTRACT['internal'])} internal paths match implementation",
          flush=True)


def check_external_contract():
    status, body = http(ISSUER + "/.well-known/openid-configuration")
    require(status == 200, "OIDC discovery unavailable for contract login")
    metadata = json.loads(body)
    admin = login(metadata, "a-admin")["access_token"]
    staff = login(metadata, "a-employee")["access_token"]

    identity = api("/api/v1/identity", 200, admin)
    require(isinstance(identity["subject"], str) and identity["tenantId"] == "tenant-a"
            and isinstance(identity["roles"], list), f"identity contract broken: {identity}")

    page = api("/api/v1/documents?page=0&size=20", 200, admin)
    require(isinstance(page["items"], list) and isinstance(page["hasNext"], bool),
            f"documents list contract broken: {page}")

    api("/api/v1/documents", 403, staff)
    api("/api/v1/documents", 403, staff, method="POST")

    today = datetime.now(timezone.utc).date().isoformat()
    usage = api(f"/api/v1/usage?date={today}", 200, admin)
    require(usage["date"] == today and isinstance(usage["currency"], str)
            and isinstance(usage["models"], list), f"usage contract broken: {usage}")
    api(f"/api/v1/usage?date={today}", 403, staff)
    api("/api/v1/usage?date=not-a-date", 400, admin)

    api("/api/v1/identity", 401)
    envelope(http(GATEWAY + "/api/v1/questions", b'{"question":""}', None, "POST")[1], "unauthenticated question")

    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + staff}
    for label, body in (
        ("empty question", b'{"question":""}'),
        ("non-string question", b'{"question":123}'),
        ("malformed json", b'{"question":'),
    ):
        status, raw = http(GATEWAY + "/api/v1/questions", body, headers, "POST")
        require(status == 400, f"{label}: expected 400, received {status}")
        envelope(raw, label)

    status, raw = http(GATEWAY + "/api/v1/questions", b"plain text",
                       {"Content-Type": "text/plain", "Authorization": "Bearer " + staff}, "POST")
    require(status == 415, f"unsupported media type: expected 415, received {status}")
    envelope(raw, "unsupported media type")

    status, _ = http(GATEWAY + "/api/v1/documents/999999/versions/1/source?unit=line&start=1&end=1",
                     None, {"Authorization": "Bearer " + staff}, "GET")
    require(status == 404, f"missing document source: expected 404, received {status}")
    print("PASS: role isolation, response schemas, error envelope, parameter and media-type validation", flush=True)


def check_internal_contract():
    valid = """
import json, os, urllib.request as http
token = os.environ['RAG_METRIC_TOKEN']
body = json.dumps({
    'user_input': 'q',
    'response': '入职满一年，可休 7 天公司年假。',
    'retrieved_contexts': ['入职满一年，可休 7 天公司年假。'],
    'reference': '入职满一年可休 7 天公司年假。',
}).encode()
request = http.Request('http://127.0.0.1:8090/internal/v1/evaluations', body,
                       {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
print(json.dumps(json.load(http.urlopen(request, timeout=5))))
"""
    result = compose("exec", "-T", "metric-service", "python", "-c", valid)
    scores = json.loads(result.stdout.strip().splitlines()[-1])
    require(isinstance(scores["faithfulness"], (int, float)) and isinstance(scores["context_recall"], (int, float)),
            f"evaluation contract broken: {scores}")

    rejected = """
import urllib.request as http, urllib.error as error
request = http.Request('http://127.0.0.1:8090/internal/v1/evaluations', b'{}',
                       {'Content-Type': 'application/json', 'Authorization': 'Bearer invalid'})
try:
    http.urlopen(request, timeout=5)
    print(200)
except error.HTTPError as failure:
    print(failure.code)
"""
    result = compose("exec", "-T", "metric-service", "python", "-c", rejected)
    require(result.stdout.strip().endswith("401"), "metric-service accepted an invalid service credential")

    direct = compose("exec", "-T", "gateway", "curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                     "-H", "X-Tenant-Id: tenant-a", "http://doc-service:8082/internal/v1/documents")
    require(direct.stdout.strip() == "401", "doc-service accepted an unauthenticated internal request")
    print("PASS: metric-service score schema, service credential rejection and internal auth", flush=True)


if __name__ == "__main__":
    check_static_contract()
    check_external_contract()
    check_internal_contract()
    print("CONTRACT_OK contractVersion=" + CONTRACT["contractVersion"], flush=True)
