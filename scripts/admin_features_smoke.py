#!/usr/bin/env python3
"""Admin-features smoke test: evaluation reports, monitoring and access directory.

Covers the three new ADMIN-only endpoints added to the gateway:

  GET  /api/v1/evals                 list archived golden-v1 evaluation reports
  GET  /api/v1/evals/{reportId}      one report including per-sample metrics
  POST /api/v1/evals                 run a fresh golden-v1 evaluation (40 samples)
  GET  /api/v1/monitoring            per-service health + aggregate metrics
  GET  /api/v1/access                Keycloak users / roles / counts directory

It also re-checks the existing login, identity, question and usage contracts
(regression), the NOT_FOUND behaviour of unknown evaluation reports and the
ADMIN-only authorisation (403 for a-employee, 401 for anonymous callers).

The script only reads business data and archives one new evaluation report per
run; evaluation reports are audit artifacts and are intentionally left in
``eval/reports`` and in the database.

Usage (run only after the gateway carrying the new endpoints is deployed):

    $env:PYTHONIOENCODING='utf-8'
    python scripts/admin_features_smoke.py

Exit code 0 only when every assertion passes.  Machine-readable results are
written to artifacts/admin_features_report.json.
"""

import json
import re
import sys
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from smoke import GATEWAY, ISSUER, NoRedirect, login  # noqa: E402

OPENER = build_opener(NoRedirect(), ProxyHandler({}))
REPORT_PATH = ROOT / "artifacts" / "admin_features_report.json"
REPORTS_DIR = ROOT / "eval" / "reports"
EVAL_TIMEOUT_SECONDS = 180
METRIC_SETTLE_SECONDS = 1.5

EVAL_ITEM_KEYS = {"reportId", "dataset", "status", "generatedAt", "sampleCount",
                  "faithfulness", "contextRecall", "reason"}
EVAL_DETAIL_REQUIRED = {"dataset", "status", "averages", "samples"}
SAMPLE_KEYS = {"userInput", "faithfulness", "contextRecall", "error"}
SERVICE_KEYS = {"name", "label", "category", "status", "latencyMs", "detail", "uptimeSeconds"}
METRIC_KEYS = {"questionCount", "questionFailures", "questionSuccessRate", "averageLatencyMs",
               "inputTokens", "outputTokens", "modelFailovers", "kafkaPendingEvents", "indexFailures"}
ACCESS_USER_KEYS = {"username", "email", "enabled", "tenantId", "roles", "createdAt"}
COUNTS_KEYS = {"total", "admins", "employees", "tenants"}
DIRECTORY_KEYS = {"available", "message"}
ERROR_KEYS = {"code", "message", "traceId"}
TRACE_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


# ---------------------------------------------------------------------------
# Assertion collector
# ---------------------------------------------------------------------------

class Checks:
    def __init__(self):
        self.items = []

    def check(self, name, condition, detail=""):
        passed = bool(condition)
        self.items.append({
            "name": name,
            "status": "PASS" if passed else "FAIL",
            "detail": "" if passed else str(detail)[:1200],
        })
        suffix = "" if passed else f"  <- {str(detail)[:600]}"
        print(("PASS  " if passed else "FAIL  ") + name + suffix, flush=True)
        return passed

    @property
    def failures(self):
        return [item for item in self.items if item["status"] == "FAIL"]

    @property
    def passes(self):
        return [item for item in self.items if item["status"] == "PASS"]


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def http(method, path, token=None, body=None, headers=None, timeout=60):
    request_headers = dict(headers or {})
    if token is not None:
        request_headers["Authorization"] = "Bearer " + token
    url = path if path.startswith("http") else GATEWAY + path
    request = Request(url, body, request_headers, method=method)
    try:
        response = OPENER.open(request, timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        payload = None
        if raw:
            try:
                payload = json.loads(raw)
            except ValueError:
                payload = None
        return {
            "status": response.status,
            "headers": {name.lower(): value for name, value in response.headers.items()},
            "raw": raw,
            "json": payload,
            "text": raw.decode("utf-8", "replace"),
        }


def describe(response):
    return f"HTTP {response['status']} body={response['text'][:700]}"


def envelope_ok(payload):
    return (isinstance(payload, dict)
            and all(isinstance(payload.get(field), str) and payload[field]
                    for field in ("code", "message", "traceId"))
            and bool(TRACE_ID.match(payload.get("traceId", ""))))


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

def report_files():
    """Existing archived evaluation report files (report-*.json)."""
    if not REPORTS_DIR.is_dir():
        return set()
    return {path.name: path for path in REPORTS_DIR.glob("report-*.json")}


def get_metadata():
    response = http("GET", ISSUER + "/.well-known/openid-configuration", timeout=30)
    if response["status"] != 200 or not isinstance(response["json"], dict):
        raise RuntimeError(f"OIDC discovery failed: {describe(response)}")
    return response["json"]


# ---------------------------------------------------------------------------
# 1) GET /api/v1/evals — archived report list
# ---------------------------------------------------------------------------

def check_evals_list(checker, token, report):
    response = http("GET", "/api/v1/evals", token)
    payload = response["json"] if isinstance(response["json"], dict) else {}
    checker.check("GET /api/v1/evals returns 200 with an items/total wrapper",
                  response["status"] == 200 and set(payload) == {"items", "total"},
                  describe(response))
    items = payload.get("items")
    total_value = payload.get("total")
    checker.check("evals list wrapper carries a non-empty items array and a matching total",
                  isinstance(items, list) and bool(items)
                  and isinstance(total_value, int) and not isinstance(total_value, bool)
                  and total_value >= 1 and total_value >= len(items),
                  f"total={total_value!r} items={len(items) if isinstance(items, list) else items!r}")

    bad = []
    for index, item in enumerate(items if isinstance(items, list) else []):
        problems = []
        if not isinstance(item, dict):
            problems.append("not an object")
        else:
            if set(item) != EVAL_ITEM_KEYS:
                problems.append(f"keys={sorted(item)}")
            if not (isinstance(item.get("reportId"), str) and item.get("reportId")):
                problems.append(f"reportId={item.get('reportId')!r}")
            elif not item["reportId"].startswith("report-"):
                problems.append(f"reportId={item.get('reportId')!r} (no report- prefix)")
            if not (isinstance(item.get("dataset"), str) and item["dataset"]):
                problems.append(f"dataset={item.get('dataset')!r}")
            if item.get("status") not in ("OK", "FAILED"):
                problems.append(f"status={item.get('status')!r}")
            if not (isinstance(item.get("generatedAt"), int) and not isinstance(item.get("generatedAt"), bool)
                    and item["generatedAt"] > 0):
                problems.append(f"generatedAt={item.get('generatedAt')!r}")
            if not (isinstance(item.get("sampleCount"), int) and item["sampleCount"] > 0):
                problems.append(f"sampleCount={item.get('sampleCount')!r}")
            for nullable_number in ("faithfulness", "contextRecall"):
                if not (item.get(nullable_number) is None or number(item.get(nullable_number))):
                    problems.append(f"{nullable_number}={item.get(nullable_number)!r}")
            if not (item.get("reason") is None or isinstance(item.get("reason"), str)):
                problems.append(f"reason={item.get('reason')!r}")
        if problems:
            bad.append({"index": index, "problems": problems, "item": item})
    checker.check("every evals list item exposes the exact UI field set with typed values",
                  not bad, json.dumps(bad[:3], ensure_ascii=False))

    timestamps = [item.get("generatedAt") for item in (items or []) if isinstance(item, dict)]
    checker.check("evals list is sorted newest first (generatedAt descending)",
                  timestamps == sorted(timestamps, reverse=True),
                  f"generatedAt={timestamps[:8]}")

    archived = [item for item in (items or [])
                if isinstance(item, dict) and item.get("status") in ("OK", "FAILED")]
    checker.check("evals list contains archived reports (OK or FAILED status)",
                  bool(archived), f"statuses={[item.get('status') for item in (items or []) if isinstance(item, dict)]}")
    checker.check("evals list contains at least one OK report with numeric faithfulness/contextRecall",
                  any(item.get("status") == "OK" and number(item.get("faithfulness"))
                      and number(item.get("contextRecall")) for item in archived),
                  f"archived={archived[:3]}")

    report["evalsList"] = {
        "total": total_value,
        "count": len(items) if isinstance(items, list) else None,
        "newest": items[0] if items else None,
    }
    return items if isinstance(items, list) else []


# ---------------------------------------------------------------------------
# 2) GET /api/v1/evals/{reportId} — report detail
# ---------------------------------------------------------------------------

def check_eval_detail(checker, token, items, report):
    if not items:
        checker.check("GET /api/v1/evals/{reportId} detail checks", False, "evals list is empty")
        return
    first = items[0]
    report_id = first.get("reportId")
    response = http("GET", f"/api/v1/evals/{report_id}", token)
    payload = response["json"] if isinstance(response["json"], dict) else {}
    checker.check(f"GET /api/v1/evals/{report_id} returns 200 with the report detail",
                  response["status"] == 200 and isinstance(response["json"], dict)
                  and EVAL_DETAIL_REQUIRED <= set(payload), describe(response))

    samples = payload.get("samples")
    sample_count = first.get("sampleCount")
    checker.check("eval detail matches the list row (dataset/status) and returns samples",
                  payload.get("dataset") == first.get("dataset")
                  and payload.get("status") == first.get("status")
                  and isinstance(samples, list) and bool(samples), f"detail={payload}")
    checker.check("eval detail samples length equals the list sampleCount",
                  isinstance(samples, list) and sample_count == len(samples),
                  f"sampleCount={sample_count} samples={len(samples) if isinstance(samples, list) else samples}")
    if isinstance(payload.get("sampleCount"), int):
        checker.check("eval detail sampleCount equals the samples array length",
                      payload["sampleCount"] == len(samples or []),
                      f"sampleCount={payload.get('sampleCount')} samples={len(samples or [])}")

    averages = payload.get("averages") if isinstance(payload.get("averages"), dict) else {}
    checker.check("eval detail averages expose faithfulness and contextRecall",
                  set(averages) == {"faithfulness", "contextRecall"}
                  and all(averages.get(key) is None or number(averages.get(key))
                          for key in ("faithfulness", "contextRecall")),
                  f"averages={averages}")
    if first.get("status") == "OK":
        checker.check("eval detail averages are within 0..1 for an OK report",
                      number(averages.get("faithfulness")) and 0 <= averages["faithfulness"] <= 1
                      and number(averages.get("contextRecall")) and 0 <= averages["contextRecall"] <= 1,
                      f"averages={averages}")

    first_sample = samples[0] if isinstance(samples, list) and samples else None
    checker.check("first eval sample exposes userInput/faithfulness/contextRecall/error",
                  isinstance(first_sample, dict) and set(first_sample) == SAMPLE_KEYS
                  and isinstance(first_sample.get("userInput"), str) and bool(first_sample.get("userInput"))
                  and (first_sample.get("faithfulness") is None or number(first_sample.get("faithfulness")))
                  and (first_sample.get("contextRecall") is None or number(first_sample.get("contextRecall")))
                  and (first_sample.get("error") is None or isinstance(first_sample.get("error"), str)),
                  f"firstSample={first_sample}")
    malformed_samples = [
        sample for sample in (samples or [])
        if not (isinstance(sample, dict) and set(sample) == SAMPLE_KEYS
                and isinstance(sample.get("userInput"), str) and sample.get("userInput"))
    ]
    checker.check("every eval sample has the same four-field shape", not malformed_samples,
                  f"badSamples={malformed_samples[:2]}")

    unknown = http("GET", "/api/v1/evals/report-999999999", token)
    checker.check("unknown evaluation report returns 404 NOT_FOUND with an error envelope",
                  unknown["status"] == 404 and envelope_ok(unknown["json"])
                  and (unknown["json"] or {}).get("code") == "NOT_FOUND", describe(unknown))
    malformed = http("GET", "/api/v1/evals/..%2Fetc", token)
    checker.check("malformed reportId is rejected (400/404, no 200 and no path traversal)",
                  malformed["status"] in (400, 404)
                  and (malformed["json"] is None or envelope_ok(malformed["json"])),
                  describe(malformed))

    report["evalDetail"] = {
        "reportId": report_id,
        "dataset": payload.get("dataset"),
        "status": payload.get("status"),
        "sampleCount": len(samples or []),
        "averages": averages,
    }


# ---------------------------------------------------------------------------
# 3) POST /api/v1/evals — run a fresh golden-v1 evaluation
# ---------------------------------------------------------------------------

def check_new_eval(checker, token, report):
    before_files = report_files()
    before_list = http("GET", "/api/v1/evals", token)
    before_total = ((before_list["json"] or {}).get("total")
                    if isinstance(before_list["json"], dict) else None)

    started = time.monotonic()
    response = http("POST", "/api/v1/evals", token, timeout=EVAL_TIMEOUT_SECONDS)
    duration = round(time.monotonic() - started, 1)
    payload = response["json"] if isinstance(response["json"], dict) else {}
    print(f"INFO  POST /api/v1/evals took {duration}s -> {response['status']}", flush=True)

    checker.check("POST /api/v1/evals runs a fresh evaluation with 200 and status OK",
                  response["status"] == 200 and payload.get("status") == "OK", describe(response))
    checker.check("fresh evaluation covers exactly 40 golden-v1 samples",
                  payload.get("sampleCount") == 40,
                  f"sampleCount={payload.get('sampleCount')!r} payload={payload}")
    if payload.get("dataset") is not None:
        checker.check("fresh evaluation reports the golden-v1 dataset",
                      payload.get("dataset") == "golden-v1", f"dataset={payload.get('dataset')!r}")

    averages = payload.get("averages") if isinstance(payload.get("averages"), dict) else {}
    faithfulness = payload.get("faithfulness", averages.get("faithfulness"))
    recall = payload.get("contextRecall", averages.get("contextRecall"))
    checker.check("fresh evaluation meets the golden faithfulness target (>= 0.90)",
                  number(faithfulness) and faithfulness >= 0.90, f"faithfulness={faithfulness!r}")
    checker.check("fresh evaluation meets the golden contextRecall target (>= 0.85)",
                  number(recall) and recall >= 0.85, f"contextRecall={recall!r}")
    checker.check("fresh evaluation finishes within the 30s budget",
                  duration <= 30, f"duration={duration}s")

    new_report_id = payload.get("reportId")
    checker.check("fresh evaluation returns a reportId", isinstance(new_report_id, str) and bool(new_report_id),
                  f"reportId={new_report_id!r} payload={payload}")

    after_list = http("GET", "/api/v1/evals", token)
    after_payload = after_list["json"] if isinstance(after_list["json"], dict) else {}
    after_ids = [item.get("reportId") for item in after_payload.get("items") or []]
    checker.check("the new evaluation appears in GET /api/v1/evals",
                  after_list["status"] == 200 and new_report_id in after_ids,
                  f"reportId={new_report_id!r} ids={after_ids[:5]}")
    after_total = after_payload.get("total")
    checker.check("evals total increases after the fresh evaluation",
                  isinstance(after_total, int) and isinstance(before_total, int)
                  and after_total == before_total + 1,
                  f"before={before_total!r} after={after_total!r}")
    newest = (after_payload.get("items") or [{}])[0]
    checker.check("the fresh report is the newest evals list item",
                  newest.get("reportId") == new_report_id,
                  f"newest={newest.get('reportId')!r} expected={new_report_id!r}")

    after_files = report_files()
    new_files = sorted(set(after_files) - set(before_files))
    checker.check("POST /api/v1/evals archives a new report-*.json file in eval/reports",
                  REPORTS_DIR.is_dir() and bool(new_files), f"newFiles={new_files} dir={REPORTS_DIR}")
    if new_files:
        newest_file = after_files[new_files[-1]]
        try:
            archived = json.loads(newest_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            archived = None
            checker.check("the archived evaluation report is valid JSON", False, f"{newest_file.name}: {error}")
        if isinstance(archived, dict):
            checker.check("the archived report is redacted and belongs to the golden-v1 dataset",
                          archived.get("redacted") is True and archived.get("dataset") == "golden-v1",
                          f"file={newest_file.name} redacted={archived.get('redacted')!r} "
                          f"dataset={archived.get('dataset')!r}")
            checker.check("the archived report stores all 40 samples and OK status",
                          archived.get("status") == "OK"
                          and isinstance(archived.get("samples"), list)
                          and len(archived["samples"]) == 40,
                          f"file={newest_file.name} status={archived.get('status')!r} "
                          f"samples={len(archived.get('samples') or [])}")
            sample_user_inputs = [sample.get("user_input") for sample in archived.get("samples") or []
                                  if isinstance(sample, dict)]
            checker.check("archived samples redact personal data (no raw phone/e-mail patterns)",
                          all(isinstance(value, str) for value in sample_user_inputs)
                          and not any(re.search(r"\b1[3-9]\d{9}\b", value)
                                      or re.search(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", value)
                                      for value in sample_user_inputs),
                          f"userInputs={sample_user_inputs[:3]}")
        report["archivedEvalFile"] = newest_file.name

    report["newEval"] = {
        "reportId": new_report_id,
        "status": payload.get("status"),
        "sampleCount": payload.get("sampleCount"),
        "dataset": payload.get("dataset"),
        "faithfulness": faithfulness,
        "contextRecall": recall,
        "durationSeconds": duration,
        "newFiles": new_files,
    }


# ---------------------------------------------------------------------------
# 4) GET /api/v1/monitoring — services + aggregate metrics
# ---------------------------------------------------------------------------

def check_monitoring(checker, token, report):
    response = http("GET", "/api/v1/monitoring", token)
    payload = response["json"] if isinstance(response["json"], dict) else {}
    checker.check("GET /api/v1/monitoring returns 200 with checkedAt/services/metrics",
                  response["status"] == 200 and set(payload) == {"checkedAt", "services", "metrics"},
                  describe(response))

    checked_at = payload.get("checkedAt")
    checker.check("monitoring checkedAt is a positive epoch timestamp",
                  isinstance(checked_at, int) and not isinstance(checked_at, bool) and checked_at > 0,
                  f"checkedAt={checked_at!r}")

    services = payload.get("services") if isinstance(payload.get("services"), list) else []
    checker.check("monitoring lists at least 9 services", len(services) >= 9, f"count={len(services)}")
    bad_services = []
    for service in services:
        if not isinstance(service, dict) or set(service) != SERVICE_KEYS:
            bad_services.append({"service": service, "problem": "keys"})
            continue
        if not isinstance(service.get("name"), str) or not service["name"]:
            bad_services.append({"service": service.get("name"), "problem": "name"})
        if service.get("status") not in ("UP", "DEGRADED", "DOWN"):
            bad_services.append({"service": service.get("name"), "problem": f"status={service.get('status')!r}"})
        if not number(service.get("latencyMs")) or service["latencyMs"] < 0:
            bad_services.append({"service": service.get("name"), "problem": f"latencyMs={service.get('latencyMs')!r}"})
        if not number(service.get("uptimeSeconds")) or service["uptimeSeconds"] < 0:
            bad_services.append({"service": service.get("name"), "problem": f"uptime={service.get('uptimeSeconds')!r}"})
    checker.check("every monitoring service exposes name/label/category/status/latencyMs/detail/uptimeSeconds",
                  bool(services) and not bad_services, f"bad={bad_services[:3]}")
    checker.check("monitoring service names are unique",
                  len({service.get("name") for service in services if isinstance(service, dict)}) == len(services),
                  f"names={[service.get('name') for service in services if isinstance(service, dict)]}")

    non_up = [service for service in services
              if isinstance(service, dict) and service.get("status") != "UP"]
    checker.check("all monitored services are UP (DEGRADED tolerated only for keycloak/opensearch)",
                  all(service.get("status") == "DEGRADED"
                      and any(token in (service.get("name") or "").lower()
                              for token in ("keycloak", "opensearch"))
                      for service in non_up),
                  f"nonUp={non_up}")

    metrics = payload.get("metrics") if isinstance(payload.get("metrics"), dict) else {}
    checker.check("monitoring metrics expose exactly the nine contracted dashboard counters",
                  set(metrics) == METRIC_KEYS, f"keys={sorted(metrics)}")
    metric_types = {
        "questionCount": lambda value: isinstance(value, int) and value >= 1,
        "questionFailures": lambda value: isinstance(value, int) and value >= 0,
        "questionSuccessRate": lambda value: number(value) and 0 <= value <= 100,
        "averageLatencyMs": lambda value: number(value) and value >= 0,
        "inputTokens": lambda value: isinstance(value, int) and value >= 0,
        "outputTokens": lambda value: isinstance(value, int) and value >= 0,
        "modelFailovers": lambda value: isinstance(value, int) and value >= 0,
        "kafkaPendingEvents": lambda value: isinstance(value, int) and value >= 0,
        "indexFailures": lambda value: isinstance(value, int) and value >= 0,
    }
    bad_metrics = {key: metrics.get(key) for key, predicate in metric_types.items()
                   if not predicate(metrics.get(key))}
    checker.check("monitoring metrics are sane (questionCount>=1 after a question, non-negative counters)",
                  not bad_metrics, f"bad={bad_metrics} metrics={metrics}")
    checker.check("monitoring metrics reflect the question asked during this run",
                  isinstance(metrics.get("questionCount"), int) and metrics["questionCount"] >= 1
                  and isinstance(metrics.get("outputTokens"), int) and metrics["outputTokens"] > 0,
                  f"metrics={metrics}")

    report["monitoring"] = {
        "serviceCount": len(services),
        "nonUpServices": [{"name": service.get("name"), "status": service.get("status")}
                          for service in non_up],
        "metrics": metrics,
        "checkedAt": checked_at,
    }


# ---------------------------------------------------------------------------
# 5) GET /api/v1/access — Keycloak users, roles and counts
# ---------------------------------------------------------------------------

def check_access(checker, token, report):
    response = http("GET", "/api/v1/access", token)
    payload = response["json"] if isinstance(response["json"], dict) else {}
    checker.check("GET /api/v1/access returns 200 with realm/directory/users/roles/counts",
                  response["status"] == 200 and set(payload) == {"realm", "directory", "users", "roles", "counts"},
                  describe(response))
    checker.check("access directory is the rag-local realm",
                  payload.get("realm") == "rag-local", f"realm={payload.get('realm')!r}")

    directory = payload.get("directory") if isinstance(payload.get("directory"), dict) else {}
    checker.check("access directory reports Keycloak as available",
                  set(directory) == DIRECTORY_KEYS and directory.get("available") is True
                  and directory.get("message") is None, f"directory={directory}")

    users = payload.get("users") if isinstance(payload.get("users"), list) else []
    checker.check("access lists at least four users (a-admin/a-employee/b-admin/b-employee)",
                  len(users) >= 4, f"count={len(users)} users={[user.get('username') for user in users if isinstance(user, dict)]}")
    bad_users = []
    for user in users:
        if not isinstance(user, dict) or set(user) != ACCESS_USER_KEYS:
            bad_users.append({"user": user, "problem": "keys"})
            continue
        if not (isinstance(user.get("username"), str) and user["username"]):
            bad_users.append({"username": user.get("username"), "problem": "username"})
        if not (isinstance(user.get("email"), str) and "@" in user["email"]):
            bad_users.append({"username": user.get("username"), "problem": f"email={user.get('email')!r}"})
        if not isinstance(user.get("enabled"), bool):
            bad_users.append({"username": user.get("username"), "problem": f"enabled={user.get('enabled')!r}"})
        if not (isinstance(user.get("tenantId"), str) and user["tenantId"]):
            bad_users.append({"username": user.get("username"), "problem": f"tenantId={user.get('tenantId')!r}"})
        if not (isinstance(user.get("roles"), list) and all(isinstance(role, str) for role in user["roles"])):
            bad_users.append({"username": user.get("username"), "problem": f"roles={user.get('roles')!r}"})
        if not (isinstance(user.get("createdAt"), str) and user["createdAt"]):
            bad_users.append({"username": user.get("username"), "problem": f"createdAt={user.get('createdAt')!r}"})
    checker.check("every access user exposes username/email/enabled/tenantId/roles/createdAt",
                  bool(users) and not bad_users, f"bad={bad_users[:3]}")

    roles = payload.get("roles")
    checker.check("access exposes the ADMIN and EMPLOYEE realm roles",
                  isinstance(roles, list) and sorted(roles) == ["ADMIN", "EMPLOYEE"],
                  f"roles={roles!r}")

    counts = payload.get("counts") if isinstance(payload.get("counts"), dict) else {}
    checker.check("access counts expose total/admins/employees/tenants",
                  set(counts) == COUNTS_KEYS, f"counts={counts}")
    tenants = counts.get("tenants")
    checker.check("access counts report at least 4 users, 2 admins, 2 employees",
                  isinstance(counts.get("total"), int) and counts["total"] >= 4
                  and isinstance(counts.get("admins"), int) and counts["admins"] >= 2
                  and isinstance(counts.get("employees"), int) and counts["employees"] >= 2,
                  f"counts={counts}")
    checker.check("access counts include the tenant-a and tenant-b tenants",
                  isinstance(tenants, (list, tuple)) and {"tenant-a", "tenant-b"} <= set(tenants),
                  f"tenants={tenants!r}")

    # Counts must agree with the user rows the UI renders.
    admins = [user for user in users if isinstance(user, dict) and "ADMIN" in (user.get("roles") or [])]
    employees = [user for user in users if isinstance(user, dict) and "EMPLOYEE" in (user.get("roles") or [])]
    checker.check("access counts agree with the user rows (admins/employees/total)",
                  len(admins) == counts.get("admins")
                  and len(employees) == counts.get("employees")
                  and len(users) == counts.get("total"),
                  f"rowsAdmins={len(admins)} rowsEmployees={len(employees)} rowsTotal={len(users)} counts={counts}")

    admin_user = next((user for user in users
                       if isinstance(user, dict) and user.get("username") == "a-admin"), None)
    checker.check("a-admin appears with ADMIN in tenant-a",
                  admin_user is not None and "ADMIN" in (admin_user.get("roles") or [])
                  and admin_user.get("tenantId") == "tenant-a", f"a-admin={admin_user}")
    employee_users = [user for user in users
                      if isinstance(user, dict) and "EMPLOYEE" in (user.get("roles") or [])]
    checker.check("employee accounts are present for tenant-a and tenant-b",
                  {"a-employee", "b-employee"} <= {user.get("username") for user in employee_users},
                  f"employees={[user.get('username') for user in employee_users]}")

    report["access"] = {
        "realm": payload.get("realm"),
        "userCount": len(users),
        "roles": roles,
        "counts": counts,
        "tenants": tenants,
    }


# ---------------------------------------------------------------------------
# 6) Authorisation: ADMIN only, anonymous rejected
# ---------------------------------------------------------------------------

def check_permissions(checker, tokens, report):
    employee = tokens["a-employee"]

    employee_evals = http("GET", "/api/v1/evals", employee)
    checker.check("a-employee gets 403 on GET /api/v1/evals with an error envelope",
                  employee_evals["status"] == 403 and envelope_ok(employee_evals["json"]),
                  describe(employee_evals))
    employee_detail = http("GET", "/api/v1/evals/report-999999999", employee)
    checker.check("a-employee gets 403 on GET /api/v1/evals/{reportId}",
                  employee_detail["status"] == 403 and envelope_ok(employee_detail["json"]),
                  describe(employee_detail))
    employee_run = http("POST", "/api/v1/evals", employee, timeout=EVAL_TIMEOUT_SECONDS)
    checker.check("a-employee gets 403 on POST /api/v1/evals",
                  employee_run["status"] == 403 and envelope_ok(employee_run["json"]),
                  describe(employee_run))
    employee_monitoring = http("GET", "/api/v1/monitoring", employee)
    checker.check("a-employee gets 403 on GET /api/v1/monitoring",
                  employee_monitoring["status"] == 403 and envelope_ok(employee_monitoring["json"]),
                  describe(employee_monitoring))
    employee_access = http("GET", "/api/v1/access", employee)
    checker.check("a-employee gets 403 on GET /api/v1/access",
                  employee_access["status"] == 403 and envelope_ok(employee_access["json"]),
                  describe(employee_access))

    anonymous_calls = [
        ("GET /api/v1/evals", lambda: http("GET", "/api/v1/evals")),
        ("GET /api/v1/evals/report-999999999", lambda: http("GET", "/api/v1/evals/report-999999999")),
        ("POST /api/v1/evals", lambda: http("POST", "/api/v1/evals")),
        ("GET /api/v1/monitoring", lambda: http("GET", "/api/v1/monitoring")),
        ("GET /api/v1/access", lambda: http("GET", "/api/v1/access")),
    ]
    for label, call in anonymous_calls:
        response = call()
        checker.check(f"anonymous {label} is rejected with 401 and an error envelope",
                      response["status"] == 401 and envelope_ok(response["json"]), describe(response))

    report["accessControl"] = {
        "employeeStatuses": {
            "GET /api/v1/evals": employee_evals["status"],
            "GET /api/v1/evals/{reportId}": employee_detail["status"],
            "POST /api/v1/evals": employee_run["status"],
            "GET /api/v1/monitoring": employee_monitoring["status"],
            "GET /api/v1/access": employee_access["status"],
        },
    }


# ---------------------------------------------------------------------------
# 7) Regression: identity, questions (with citation markers) and usage
# ---------------------------------------------------------------------------

def check_regression(checker, tokens, report):
    admin = tokens["a-admin"]

    identity = http("GET", "/api/v1/identity", admin)
    identity_payload = identity["json"] if isinstance(identity["json"], dict) else {}
    checker.check("regression: GET /api/v1/identity still returns tenant-a ADMIN for a-admin",
                  identity["status"] == 200 and identity_payload.get("tenantId") == "tenant-a"
                  and identity_payload.get("roles") == ["ADMIN"]
                  and isinstance(identity_payload.get("subject"), str) and identity_payload["subject"],
                  describe(identity))

    question = http("POST", "/api/v1/questions",
                    admin, json.dumps({"question": "出差住宿标准是多少？"}).encode("utf-8"),
                    {"Content-Type": "application/json"})
    question_payload = question["json"] if isinstance(question["json"], dict) else {}
    answer = question_payload.get("answer") or ""
    checker.check("regression: seeded question is still answered with citation markers",
                  question["status"] == 200 and question_payload.get("status") == "ANSWERED"
                  and isinstance(answer, str) and " [1]" in answer,
                  describe(question))
    checker.check("regression: the regression answer cites a chunk",
                  isinstance(question_payload.get("citations"), list) and bool(question_payload["citations"]),
                  f"citations={question_payload.get('citations')}")

    today = datetime.now(timezone.utc).date().isoformat()
    usage = http("GET", f"/api/v1/usage?date={today}", admin)
    usage_payload = usage["json"] if isinstance(usage["json"], dict) else {}
    checker.check("regression: GET /api/v1/usage still returns date/currency/models",
                  usage["status"] == 200 and set(usage_payload) == {"date", "currency", "models"}
                  and usage_payload.get("date") == today
                  and isinstance(usage_payload.get("models"), list), describe(usage))

    report["regression"] = {
        "identity": {"tenantId": identity_payload.get("tenantId"), "roles": identity_payload.get("roles")},
        "questionAnswer": answer,
        "questionStatus": question_payload.get("status"),
        "usage": usage_payload,
    }
    return answer


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    started_at = datetime.now(timezone.utc)
    checker = Checks()
    report = {
        "generatedAt": started_at.isoformat(),
        "gateway": GATEWAY,
        "issuer": ISSUER,
        "checks": checker.items,
        "deviations": [],
        "notes": [
            "Evaluation reports are audit artifacts: each run archives one report and leaves it "
            "in eval/reports and in the database by design.",
        ],
    }
    exit_code = 0
    try:
        metadata = get_metadata()
        tokens = {username: login(metadata, username)["access_token"]
                  for username in ("a-admin", "a-employee")}
        report["accounts"] = ["a-admin", "a-employee"]
        admin = tokens["a-admin"]

        items = check_evals_list(checker, admin, report)
        check_eval_detail(checker, admin, items, report)
        check_permissions(checker, tokens, report)

        answer = check_regression(checker, tokens, report)
        report["metricsWarmupAnswer"] = answer
        time.sleep(METRIC_SETTLE_SECONDS)
        check_monitoring(checker, admin, report)
        check_access(checker, admin, report)
        check_new_eval(checker, admin, report)

        report["summary"] = {
            "checks": len(checker.items),
            "passed": len(checker.passes),
            "failed": len(checker.failures),
            "durationSeconds": round((datetime.now(timezone.utc) - started_at).total_seconds(), 1),
        }
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    except Exception as error:  # noqa: BLE001 - report unexpected failures instead of only a traceback
        report["fatalError"] = f"{type(error).__name__}: {error}"
        report["fatalTraceback"] = traceback.format_exc()
        print(f"FAIL  unexpected error: {type(error).__name__}: {error}", flush=True)
        traceback.print_exc()
    finally:
        REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
        REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("", flush=True)
    if "summary" in report:
        summary = report["summary"]
        print(f"SUMMARY  checks={summary['checks']} passed={summary['passed']} "
              f"failed={summary['failed']} duration={summary['durationSeconds']}s", flush=True)
    else:
        print(f"SUMMARY  fatal error, checks={len(checker.items)} passed={len(checker.passes)} "
              f"failed={len(checker.failures)}", flush=True)
    print(f"REPORT   {REPORT_PATH.relative_to(ROOT)}", flush=True)

    if checker.failures or "summary" not in report:
        exit_code = 1
    else:
        print("PASS  admin features smoke test is green", flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
