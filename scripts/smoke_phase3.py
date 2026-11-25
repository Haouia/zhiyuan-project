import json
import re
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode

from smoke import GATEWAY, ISSUER, NoRedirect, ProxyHandler, Request, build_opener, compose, login, request, require
from smoke_phase2 import INDEX, MARKER, cleanup, filters, os_request, upload, wait_status


QUESTION = "入职满一年能休几天年假？"
DOCUMENTS = "/api/v1/documents"


def call(path, expected, token=None, body=None, headers=None):
    headers = dict(headers or {})
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    opener = build_opener(NoRedirect(), ProxyHandler({}))
    payload = None if body is None else json.dumps(body).encode("utf-8")
    try:
        response = opener.open(Request(GATEWAY + path, payload, headers), timeout=50)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        require(response.status == expected,
                f"{path}: expected HTTP {expected}, got {response.status}: {raw[:450].decode('utf-8', errors='replace')}")
    result = json.loads(raw)
    if expected >= 400:
        require(all(result.get(field) for field in ("code", "message", "traceId")), f"Incomplete error for {path}")
    return result


def ask(token, question=QUESTION, expected=200, headers=None):
    return call("/api/v1/questions", expected, token, {"question": question}, headers)


def source_url(citation):
    return (f"{DOCUMENTS}/{citation['documentId']}/versions/{citation['version']}/source?"
            + urlencode(citation["locator"]))


def citation_for(reply, document, version, days):
    require(reply["status"] == "ANSWERED" and reply["traceId"], f"Expected a validated answer, got {reply['status']}")
    days_in_answer = re.search(r"(\d+)\s*天", reply["answer"])
    require(days_in_answer is not None and int(days_in_answer.group(1)) == days, f"Wrong answer for expected {days} days")
    refs = reply["citations"]
    require(1 <= len(refs) <= 5 and all(ref["documentId"] == document and ref["version"] == version for ref in refs),
            "Unexpected tenant or document version in the citations")
    require([int(index) for index in re.findall(r"\[(\d+)]", reply["answer"])] == list(range(1, len(refs) + 1)),
            "Citation markers do not match their references")
    return refs[0]


def wait_recovered(token, seconds=90):
    deadline = time.monotonic() + seconds
    while True:
        try:
            return ask(token)
        except AssertionError:
            if time.monotonic() > deadline:
                raise
            time.sleep(2)


def accounts(names):
    status, _, raw = request(build_opener(NoRedirect(), ProxyHandler({})), ISSUER + "/.well-known/openid-configuration")
    require(status == 200, "Test OIDC issuer unavailable")
    metadata = json.loads(raw)
    return [login(metadata, user)["access_token"] for user in names]


def policy(admin, days, *, title=None, path=DOCUMENTS, long_version=False):
    line = f"入职满一年，可休 {days} 天公司年假。"
    if long_version:
        line = line + "附" * (800 - len(line))
        line = "\n".join([line] * 30)
    return upload(admin, path, title, "policy.txt", line.encode(), uuid.uuid4().hex)


def check():
    a_admin, b_admin, a_staff, b_staff = accounts(("a-admin", "b-admin", "a-employee", "b-employee"))
    ask(None, expected=401)
    ask(a_staff, "  ", expected=400)
    ask(a_staff, 17, expected=400)
    ask(a_staff, "国" * 1001, expected=400)
    a = policy(a_admin, 7, title=MARKER + "年假政策", long_version=True)["documentId"]
    b = policy(b_admin, 13, title=MARKER + "年假政策")["documentId"]
    base = f"{DOCUMENTS}/{a}/versions"
    wait_status(a_admin, base + "/1", "READY", 1)
    wait_status(b_admin, f"{DOCUMENTS}/{b}/versions/1", "READY", 1)
    count = os_request(INDEX + "/_count", {"query": {"bool": {"filter": filters("tenant-a", a, 1)}}})["count"]
    require(count > 20, "The historical version does not exceed the first retrieval window")

    first = citation_for(ask(a_staff), a, 1, 7)
    other = citation_for(ask(b_staff, headers={"X-Tenant-Id": "tenant-a"}), b, 1, 13)
    require(first["locator"]["unit"] == "line" and other["locator"] == {"unit": "line", "start": 1, "end": 1},
            "Citations do not identify their original lines")
    first_source = call(source_url(first), 200, a_staff)
    require(first["quote"] in first_source["text"] and first_source["version"] == 1, "Version one quote did not resolve")
    require(other["quote"] in call(source_url(other), 200, b_staff)["text"], "Tenant B quote did not resolve")
    call(source_url(first), 404, b_staff, headers={"X-Tenant-Id": "tenant-a"})
    call(base + "/1/source?unit=line&start=0&end=1", 400, a_staff)
    call(base + "/1/source?unit=page&start=1&end=1", 404, a_staff)
    upload(a_staff, DOCUMENTS, MARKER + "无权限上传", "policy.txt", b"staff", uuid.uuid4().hex, 403)
    refused = ask(a_staff, "海外差旅每天的住宿预算是多少？")
    require(refused["status"] == "INSUFFICIENT_EVIDENCE" and not refused["citations"], "An unsupported question got citations")
    print("PASS: both tenants answer from their own documents, source permissions and refusal", flush=True)

    compose("pause", "kafka")
    try:
        v2 = policy(a_admin, 9, path=base)
        require(v2["version"] == 2, "The replacement did not create version two")
        wait_status(a_admin, base + "/2", "INDEXING", 1)
        citation_for(ask(a_staff), a, 1, 7)
        call(base + "/2/source?unit=line&start=1&end=1", 404, a_staff)
    finally:
        compose("unpause", "kafka")
    wait_status(a_admin, base + "/2", "READY", 2)
    current = citation_for(ask(a_staff), a, 2, 9)
    require(current["quote"] in call(source_url(current), 200, a_staff)["text"], "Current quote did not resolve")
    require(call(source_url(first), 200, a_staff) == first_source, "Replacing a document changed its historical source")
    citation_for(ask(b_staff), b, 1, 13)
    print("PASS: pending update keeps v1, new questions use v2, historical v1 citations still resolve", flush=True)

    for service in ("search-service", "doc-service"):
        compose("pause", service)
        try:
            error = ask(a_staff, expected=503)
            require(error["code"] == "RETRIEVAL_UNAVAILABLE", f"Wrong error when {service} was unavailable")
        finally:
            compose("unpause", service)
        wait_recovered(a_staff)
    citation_for(ask(a_staff), a, 2, 9)
    print("PASS: dependency outages return 503 and the same question succeeds after recovery", flush=True)


if __name__ == "__main__":
    try:
        check()
    finally:
        cleanup()
