import json
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from smoke import GATEWAY, NoRedirect, ROOT, require
from smoke_phase2 import MARKER, cleanup, upload, wait_status
from smoke_phase3 import accounts, ask, citation_for

PRIMARY = "http://127.0.0.1:8095"
BACKUP = "http://127.0.0.1:8096"
DOCUMENTS = "/api/v1/documents"
QUESTION = "入职满一年能休几天年假？"
COOLDOWN_SECONDS = 9


def fixture(base, path="/state", payload=None, expected=200, timeout=15):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if body is None else {"Content-Type": "application/json"}
    opener = build_opener(NoRedirect(), ProxyHandler({}))
    try:
        response = opener.open(Request(base + path, body, headers), timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        require(response.status == expected,
                f"Fixture {base}{path} returned HTTP {response.status}: {raw[:300]!r}")
        return json.loads(raw)


def control(base, mode=None, reset=False):
    payload = {"reset": reset}
    if mode is not None:
        payload["mode"] = mode
    return fixture(base, "/control", payload)


def attempts(base):
    return len(fixture(base)["attempts"])


def redis_flush():
    keys = subprocess.run(
        ["docker", "compose", "exec", "-T", "redis", "redis-cli", "--scan", "--pattern", "rag:*"],
        cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace", timeout=30,
    ).stdout.split()
    for key in keys:
        subprocess.run(
            ["docker", "compose", "exec", "-T", "redis", "redis-cli", "DEL", key],
            cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace", timeout=30,
        )


def usage(token, date, expected=200):
    from smoke import api
    return api(f"/api/v1/usage?date={date}", expected, token)


def row(report, model):
    matches = [item for item in report["models"] if item["model"] == model]
    require(len(matches) == 1, f"Expected exactly one usage row for {model}: {report}")
    return matches[0]


def raw_ask(token, question=QUESTION):
    opener = build_opener(NoRedirect(), ProxyHandler({}))
    payload = json.dumps({"question": question}).encode("utf-8")
    request = Request(GATEWAY + "/api/v1/questions", payload,
                      {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        response = opener.open(request, timeout=50)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def eval_job(check=True):
    from smoke import compose
    return compose("run", "--rm", "--no-deps", "metric-eval", check=check)


def latest_report():
    files = list((ROOT / "eval" / "reports").glob("report-*.json"))
    require(files, "No evaluation report was archived")
    latest = max(files, key=lambda item: item.stat().st_mtime)
    return json.loads(latest.read_text(encoding="utf-8"))


def health(service):
    result = subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", f"rag-knowledge-{service}-1"],
                            cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace", timeout=30)
    return result.stdout.strip()


def wait_healthy(service, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if health(service) == "healthy":
            return
        time.sleep(2)
    raise AssertionError(f"{service} did not recover")


def check():
    a_admin, a_staff, b_admin, b_staff = accounts(("a-admin", "a-employee", "b-admin", "b-employee"))
    today = datetime.now(timezone.utc).date().isoformat()
    redis_flush()
    control(PRIMARY, "ok", reset=True)
    control(BACKUP, "ok", reset=True)
    previous_reports = {item.name for item in (ROOT / "eval" / "reports").glob("report-*.json")}

    doc = upload(a_admin, DOCUMENTS, MARKER + "治理年假政策", "policy.txt",
                 "入职满一年，可休 7 天公司年假。".encode(), uuid.uuid4().hex)["documentId"]
    other = upload(b_admin, DOCUMENTS, MARKER + "B 租户年假政策", "policy.txt",
                   "入职满一年，可休 13 天公司年假。".encode(), uuid.uuid4().hex)["documentId"]
    wait_status(a_admin, f"{DOCUMENTS}/{doc}/versions/1", "READY", 1)
    wait_status(b_admin, f"{DOCUMENTS}/{other}/versions/1", "READY", 1)

    # 基准：主键调用主端点并计量，管理员可查看用量，员工不可查看
    citation_for(ask(a_staff), doc, 1, 7)
    require(attempts(PRIMARY) == 1 and attempts(BACKUP) == 0, "Baseline did not call only the primary endpoint")
    report = usage(a_admin, today)
    primary = row(report, "fixture-primary")
    require(report["currency"] == "CNY" and primary["inputTokens"] > 0 and primary["outputTokens"] > 0
            and primary["reconcilingAttempts"] == 0 and float(primary["estimatedCost"]) > 0,
            f"Baseline usage was not metered: {primary}")
    usage(a_staff, today, 403)
    usage(a_admin, "2026-13-01", 400)
    usage(a_admin, "not-a-date", 400)
    print("PASS: per-attempt billing, cost estimate and administrator-only usage query", flush=True)

    # 主端点超时：切备成功，主端点列入待对账，备端点产生计费
    control(PRIMARY, "timeout")
    citation_for(ask(a_staff), doc, 1, 7)
    require(attempts(PRIMARY) == 2 and attempts(BACKUP) == 1, "Timeout did not fail over exactly once")
    report = usage(a_admin, today)
    require(row(report, "fixture-primary")["reconcilingAttempts"] == 1, "Timed-out attempt was not marked for reconciliation")
    backup = row(report, "fixture-backup")
    require(backup["inputTokens"] > 0 and backup["outputTokens"] > 0, "Backup endpoint usage was not metered")

    # 双端点故障：503
    control(BACKUP, "500")
    error = ask(a_staff, expected=503)
    require(error["code"] == "MODEL_UNAVAILABLE", f"Wrong error for both endpoints down: {error}")

    # 主端点连续两次失败后熔断打开：下一次请求直接走备端点
    control(BACKUP, "ok")
    before_primary, before_backup = attempts(PRIMARY), attempts(BACKUP)
    citation_for(ask(a_staff), doc, 1, 7)
    require(attempts(PRIMARY) == before_primary and attempts(BACKUP) == before_backup + 1,
            "Open breaker did not skip the failing primary endpoint")

    # 冷却后半开探测成功并恢复主端点
    control(PRIMARY, "ok")
    time.sleep(COOLDOWN_SECONDS)
    before_primary = attempts(PRIMARY)
    citation_for(ask(a_staff), doc, 1, 7)
    require(attempts(PRIMARY) == before_primary + 1, "Half-open probe did not recover the primary endpoint")
    print("PASS: single-endpoint failover, both-endpoint outage, breaker and half-open recovery", flush=True)

    # 租户限额：429 + Retry-After，且超额请求不触发任何供应商调用
    begin = attempts(PRIMARY) + attempts(BACKUP)
    successes = 0
    limited = False
    for _ in range(25):
        status, headers, raw = raw_ask(a_staff)
        if status == 200:
            successes += 1
            continue
        require(status == 429, f"Unexpected status {status} while testing quota: {raw[:300]!r}")
        retry_after = headers.get("Retry-After")
        require(retry_after and retry_after.isdigit() and 1 <= int(retry_after) <= 60,
                f"Missing or invalid Retry-After: {retry_after}")
        require(json.loads(raw)["code"] == "QUOTA_EXCEEDED", "Wrong error code for over-quota request")
        limited = True
        break
    require(limited, "Tenant quota never returned 429")
    require(attempts(PRIMARY) + attempts(BACKUP) - begin == successes,
            "Supplier calls changed for rate-limited questions")
    print("PASS: tenant quota returns 429 with Retry-After and blocks supplier calls", flush=True)

    # 评测停机：在线问答正常；离线作业失败但保留原因和空分数；恢复后同一批样本可重跑
    from smoke import compose
    compose("pause", "metric-service")
    try:
        citation_for(ask(b_staff), other, 1, 13)
        failed = eval_job(check=False)
        require(failed.returncode != 0, "Offline evaluation succeeded while metric-service was down")
        report = latest_report()
        require({item.name for item in (ROOT / "eval" / "reports").glob("report-*.json")} - previous_reports,
                "No new report was archived for the failed evaluation")
        require(report["status"] == "FAILED" and report["reason"]
                and all(item["faithfulness"] is None and item["context_recall"] is None for item in report["samples"]),
                f"Failed evaluation did not retain a reason and empty scores: {report}")
    finally:
        compose("unpause", "metric-service")
    wait_healthy("metric-service")
    success = eval_job()
    require(success.returncode == 0 and "Evaluation OK" in success.stdout, "Offline evaluation did not rerun successfully")
    report = latest_report()
    require(report["status"] == "OK" and not report["reason"], f"Rerun report is not clean: {report}")
    require(abs(report["samples"][0]["faithfulness"] - 1.0) < 1e-6
            and report["samples"][1]["context_recall"] is None
            and abs(report["samples"][2]["faithfulness"]) < 1e-6,
            f"Unexpected RAGAS-style scores: {report['samples']}")
    require(report["averages"]["faithfulness"] is not None and report["averages"]["context_recall"] is not None,
            "Evaluation report did not archive averages")
    print("PASS: online questions survive metric outage; offline job fails transparently and reruns into a report", flush=True)


if __name__ == "__main__":
    try:
        check()
    finally:
        control(PRIMARY, "ok", reset=True)
        control(BACKUP, "ok", reset=True)
        cleanup()
        redis_flush()
