#!/usr/bin/env python3
"""Live API smoke for multi-turn questions (optional ``history``) on the enterprise RAG gateway.

Covers the task-14 contract:
  1. single-turn question keeps the old shape (ANSWERED, " [1]" markers, traceId);
  2. a pronoun follow-up resolves through ``history`` (>=1 citation from 差旅与费用报销标准),
     while the same follow-up without history must stay INSUFFICIENT_EVIDENCE or cite
     unrelated documents;
  3. history is never evidence: every citation quote must appear verbatim both in the
     retrieved chunk (search_db.doc_chunk) and in GET .../source?unit=line&start=&end=;
  4. history caps/trimming: 10 synthetic turns, 5000-char answer, non-array history
     values all stay HTTP 200 (no 5xx) and behave like a single turn;
  5. question validation and access rules are unchanged (400 envelope, 401 anonymous,
     EMPLOYEE may ask with history);
  6. regression: admin_features_smoke.py and register_invite_smoke.py stay green and
     identity/usage endpoints are unchanged.

Exit code is 0 only when every check passes. The JSON report is written to
artifacts/multiturn_report.json. The script is read-only against documents (it never
uploads, deletes or mutates them) and only asks questions plus reads MySQL.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

from smoke import GATEWAY, ISSUER, NoRedirect, login, mysql  # noqa: E402

OPENER = build_opener(NoRedirect(), ProxyHandler({}))
REPORT_PATH = ROOT / "artifacts" / "multiturn_report.json"
TENANT = "tenant-a"
TRAVEL_DOC_ID = "117"
TRAVEL_TITLE = "差旅与费用报销标准"
TRAVEL_QUESTION = "出差住宿标准是多少？"
FOLLOW_UP_QUESTION = "它适用于哪些城市？"
INSUFFICIENT_ANSWER = "现有资料不足以回答这个问题。"
MAX_QUESTION_LENGTH = 1000
HISTORY_MARKER = "HIST-ONLY-MARKER-FIN-TRV-2026-07"

CHUNK_ID_PATTERN = re.compile(r"^(?P<tenant>[a-z0-9-]+):(?P<document>\d+):(?P<version>\d+):(?P<chunk>\d+)$")
CITATION_KEYS = {"documentId", "title", "version", "chunkId", "locator", "quote"}
LOCATOR_KEYS = {"unit", "start", "end"}
TRACE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{3,96}$")
CHUNK_UNITS = {"line", "page", "paragraph"}

REGRESSION_TARGETS = (
    ("admin_features_smoke.py", 65, "evals/monitoring/access smoke"),
    ("register_invite_smoke.py", 97, "registration/invite/promotion smoke"),
)

QUESTION_LOG = []
RATE_LIMIT_WAITS = []
SOURCE_CACHE = {}
CHUNK_CACHE = {}
LINE_COUNT_CACHE = {}


class Response:
    def __init__(self, status, headers, text):
        self.status = status
        self.headers = headers
        self.text = text
        self.payload = None
        try:
            self.payload = json.loads(text)
        except (ValueError, TypeError):
            self.payload = None


def describe(response, limit=320):
    body = (response.text or "").replace("\n", "\\n")
    return f"HTTP {response.status} {body[:limit]}"


def http(method, path, token=None, body=None, raw=None, content_type="application/json", timeout=120):
    url = path if path.startswith("http") else GATEWAY + path
    headers = {"Accept": "application/json"}
    data = None
    if raw is not None:
        data = raw
        headers["Content-Type"] = content_type
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=timeout) as response:
            return Response(response.status, dict(response.headers), response.read().decode("utf-8", "replace"))
    except HTTPError as error:
        return Response(error.code, dict(error.headers), error.read().decode("utf-8", "replace"))


class Checks:
    def __init__(self):
        self.items = []
        self.passes = 0
        self.failures = 0
        self.deviations = []

    def add(self, name, ok, detail=""):
        detail = str(detail)
        if ok:
            self.passes += 1
            print(f"PASS  {name}", flush=True)
        else:
            self.failures += 1
            print(f"FAIL  {name}" + (f"  <- {detail}" if detail else ""), flush=True)
        self.items.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail})

    def check(self, name, condition, detail=""):
        self.add(name, bool(condition), detail)
        return bool(condition)

    def deviation(self, note):
        self.deviations.append(note)

    def summary(self, duration):
        return {
            "checks": len(self.items),
            "passed": self.passes,
            "failed": self.failures,
            "durationSeconds": round(duration, 1),
        }


def get_metadata():
    response = http("GET", ISSUER + "/.well-known/openid-configuration")
    if response.status != 200 or not isinstance(response.payload, dict):
        raise RuntimeError(f"OIDC discovery failed: {describe(response)}")
    return response.payload


def ask(token, body, label):
    """POST /api/v1/questions, retrying on the documented 429 quota response."""
    for attempt in range(3):
        response = http("POST", "/api/v1/questions", token=token, body=body)
        QUESTION_LOG.append({"label": label, "attempt": attempt + 1, "status": response.status,
                             "body": json.dumps(body, ensure_ascii=False)[:400]})
        if response.status != 429:
            return response
        retry_after = response.headers.get("Retry-After")
        wait = max(1, int(retry_after)) if str(retry_after or "").strip().isdigit() else 20
        RATE_LIMIT_WAITS.append({"label": label, "retryAfterSeconds": wait})
        print(f"WAIT  {label}: HTTP 429 QUOTA_EXCEEDED, sleeping {wait}s before retrying", flush=True)
        time.sleep(wait + 1)
    return response


def envelope_check(checks, label, response, expected_status, expected_code=None):
    checks.check(f"{label}: responded HTTP {expected_status}", response.status == expected_status, describe(response))
    payload = response.payload if isinstance(response.payload, dict) else {}
    fields = ("code", "message", "traceId")
    checks.check(f"{label}: error envelope has code/message/traceId",
                 all(isinstance(payload.get(field), str) and payload.get(field).strip() for field in fields),
                 f"payload={json.dumps(payload, ensure_ascii=False)[:300]}")
    if expected_code is not None:
        checks.check(f"{label}: code is {expected_code}", payload.get("code") == expected_code,
                     f"code={payload.get('code')!r}")
    return payload


def check_identity(checks, label, response):
    checks.check(f"{label}: HTTP 200", response.status == 200, describe(response))
    payload = response.payload if isinstance(response.payload, dict) else {}
    checks.check(f"{label}: exposes subject/tenantId/roles",
                 isinstance(payload.get("subject"), str) and isinstance(payload.get("tenantId"), str)
                 and isinstance(payload.get("roles"), list), json.dumps(payload, ensure_ascii=False)[:300])
    return payload


# --------------------------------------------------------------------------- source / chunk helpers

def line_count(document_id, version):
    key = (document_id, version)
    if key in LINE_COUNT_CACHE:
        return LINE_COUNT_CACHE[key]
    statement = (
        "SELECT JSON_LENGTH(positions->'$.positions') FROM doc_db.kb_document_version "
        f"WHERE document_id={int(document_id)} AND version={int(version)};"
    )
    result = mysql(statement, check=False)
    value = None
    if result.returncode == 0 and result.stdout.strip().isdigit():
        value = int(result.stdout.strip())
    LINE_COUNT_CACHE[key] = value
    return value


def source_text(document_id, version, token):
    """Full parsed source text of one READY version through the public source endpoint."""
    key = (document_id, version)
    if key in SOURCE_CACHE:
        return SOURCE_CACHE[key]
    count = line_count(document_id, version)
    value = None
    if count:
        response = http(
            "GET",
            f"/api/v1/documents/{document_id}/versions/{version}/source?unit=line&start=1&end={count}",
            token=token,
            timeout=60,
        )
        if response.status == 200 and isinstance(response.payload, dict):
            value = response.payload.get("text")
    SOURCE_CACHE[key] = value
    return value


def chunk_text(tenant, document_id, version, chunk_index):
    """Exact chunk text stored by the search index for one chunkId."""
    key = (tenant, document_id, version, chunk_index)
    if key in CHUNK_CACHE:
        return CHUNK_CACHE[key]
    statement = (
        "SELECT chunk_text FROM search_db.doc_chunk "
        f"WHERE tenant_id='{tenant}' AND document_id={int(document_id)} AND version={int(version)} "
        f"AND chunk_index={int(chunk_index)} LIMIT 1;"
    )
    result = mysql(statement, check=False)
    value = None
    if result.returncode == 0:
        text = result.stdout
        if text.endswith("\n"):
            text = text[:-1]
        if text:
            value = text
    CHUNK_CACHE[key] = value
    return value


def validate_citations(checks, label, payload, token, tenant=TENANT, expect_document=None):
    citations = payload.get("citations")
    checks.check(f"{label}: citations is a list", isinstance(citations, list),
                 f"type={type(citations).__name__}")
    citations = citations if isinstance(citations, list) else []
    checks.check(f"{label}: has at least one citation", len(citations) >= 1, f"citations={len(citations)}")
    verified = 0
    for position, citation in enumerate(citations, start=1):
        prefix = f"{label}: citation {position}"
        if not isinstance(citation, dict):
            checks.check(f"{prefix} is a JSON object", False, f"value={citation!r}")
            continue
        checks.check(f"{prefix} has exactly the contracted keys", set(citation.keys()) == CITATION_KEYS,
                     f"keys={sorted(citation.keys())}")
        document_id = str(citation.get("documentId", ""))
        title = citation.get("title")
        version = citation.get("version")
        chunk_id = citation.get("chunkId")
        locator = citation.get("locator")
        quote = citation.get("quote")
        checks.check(f"{prefix} documentId is a numeric string", document_id.isdigit(), f"documentId={document_id!r}")
        checks.check(f"{prefix} title is a non-empty string", isinstance(title, str) and bool(title.strip()),
                     f"title={title!r}")
        checks.check(f"{prefix} version is a positive integer", isinstance(version, int) and version >= 1,
                     f"version={version!r}")
        match = CHUNK_ID_PATTERN.match(chunk_id) if isinstance(chunk_id, str) else None
        checks.check(f"{prefix} chunkId matches tenant:document:version:chunk", bool(match), f"chunkId={chunk_id!r}")
        if match:
            checks.check(f"{prefix} chunkId agrees with documentId/version",
                         match.group("document") == document_id and int(match.group("version")) == version,
                         f"chunkId={chunk_id!r} vs documentId={document_id!r} version={version!r}")
        checks.check(f"{prefix} locator has unit/start/end",
                     isinstance(locator, dict) and set(locator.keys()) == LOCATOR_KEYS
                     and locator.get("unit") in CHUNK_UNITS
                     and isinstance(locator.get("start"), int) and isinstance(locator.get("end"), int)
                     and 1 <= locator.get("start", 0) <= locator.get("end", 0),
                     f"locator={locator!r}")
        checks.check(f"{prefix} quote is a non-empty string", isinstance(quote, str) and bool(quote.strip()),
                     f"quote={quote!r}")
        if not (document_id.isdigit() and isinstance(version, int) and isinstance(quote, str) and quote.strip()):
            continue
        if expect_document is not None:
            checks.check(f"{prefix} cites the expected document {expect_document}", document_id == expect_document,
                         f"documentId={document_id!r}")
        if match:
            chunk = chunk_text(tenant, document_id, version, int(match.group("chunk")))
            chunk_plain = None if chunk is None else chunk.replace("\\n", "\n").replace("\\t", "\t")
            checks.check(f"{prefix} quote appears verbatim in the retrieved chunk",
                         chunk is not None and (quote in chunk or quote in chunk_plain),
                         f"chunkId={chunk_id!r} chunk={'missing' if chunk is None else chunk[:160] + '...'}")
        source = source_text(document_id, version, token)
        checks.check(f"{prefix} quote appears verbatim in GET .../source text",
                     source is not None and quote in source,
                     f"source={'unavailable' if source is None else source[:120] + '...'}")
        checks.check(f"{prefix} quote does not come from the fabricated history text",
                     HISTORY_MARKER not in quote, f"quote={quote!r}")
        verified += 1
    return verified


def check_answered_answer(checks, label, payload):
    answer = payload.get("answer")
    checks.check(f"{label}: answer is a non-empty string", isinstance(answer, str) and bool(answer.strip()),
                 f"answer={answer!r}")
    if not isinstance(answer, str):
        return
    if payload.get("status") == "ANSWERED":
        checks.check(f"{label}: ANSWERED answer carries the ' [1]' marker", " [1]" in answer,
                     f"answer={answer[:200]!r}")
        checks.check(f"{label}: ANSWERED answer is not the INSUFFICIENT text", answer.strip() != INSUFFICIENT_ANSWER,
                     f"answer={answer[:200]!r}")
    else:
        checks.check(f"{label}: INSUFFICIENT_EVIDENCE answer uses the documented text",
                     answer.strip() == INSUFFICIENT_ANSWER, f"answer={answer!r}")
        checks.check(f"{label}: INSUFFICIENT_EVIDENCE has no citations", payload.get("citations") == [],
                     f"citations={payload.get('citations')!r}")


def answer_payload(checks, label, response, expected_status=200):
    checks.check(f"{label}: HTTP {expected_status}", response.status == expected_status, describe(response))
    payload = response.payload if isinstance(response.payload, dict) else {}
    if response.status == expected_status:
        checks.check(f"{label}: body is a JSON object", isinstance(response.payload, dict),
                     f"body={response.text[:200]!r}")
        checks.check(f"{label}: status is ANSWERED or INSUFFICIENT_EVIDENCE",
                     payload.get("status") in ("ANSWERED", "INSUFFICIENT_EVIDENCE"),
                     f"status={payload.get('status')!r}")
        trace_id = payload.get("traceId")
        checks.check(f"{label}: traceId is present and well formed",
                     isinstance(trace_id, str) and bool(TRACE_ID_PATTERN.match(trace_id)), f"traceId={trace_id!r}")
        check_answered_answer(checks, label, payload)
    return payload


def citation_summary(payload):
    citations = payload.get("citations") or []
    return {
        "status": payload.get("status"),
        "answer": payload.get("answer"),
        "citationCount": len(citations),
        "citations": [
            {"documentId": str(citation.get("documentId")), "title": citation.get("title"),
             "version": citation.get("version"), "chunkId": citation.get("chunkId"),
             "quote": citation.get("quote")}
            for citation in citations if isinstance(citation, dict)
        ],
    }


def travel_citation_count(payload):
    return sum(1 for citation in (payload.get("citations") or [])
               if isinstance(citation, dict) and str(citation.get("documentId")) == TRAVEL_DOC_ID)


# --------------------------------------------------------------------------- phases

def check_seed_documents(checks, admin_token):
    response = http("GET", "/api/v1/documents?page=0&size=100", token=admin_token)
    checks.check("documents list: HTTP 200", response.status == 200, describe(response))
    payload = response.payload if isinstance(response.payload, dict) else {}
    items = payload.get("items") if isinstance(payload.get("items"), list) else []
    travel = [item for item in items if isinstance(item, dict) and str(item.get("documentId")) == TRAVEL_DOC_ID]
    checks.check(f"seed document {TRAVEL_DOC_ID} ({TRAVEL_TITLE}) exists", bool(travel),
                 f"titles={[item.get('title') for item in items if isinstance(item, dict)]}")
    if travel:
        row = travel[0]
        checks.check(f"seed document {TRAVEL_DOC_ID} is READY",
                     row.get("status") == "READY" and row.get("activeVersion") == 1,
                     f"row={json.dumps(row, ensure_ascii=False)}")
        checks.check(f"seed document {TRAVEL_DOC_ID} title is {TRAVEL_TITLE}", row.get("title") == TRAVEL_TITLE,
                     f"title={row.get('title')!r}")
    return items


def check_single_turn(checks, admin_token, diagnostics):
    label = "single-turn " + TRAVEL_QUESTION
    response = ask(admin_token, {"question": TRAVEL_QUESTION}, label)
    payload = answer_payload(checks, label, response)
    if response.status == 200 and isinstance(response.payload, dict):
        checks.check(f"{label}: status is ANSWERED", payload.get("status") == "ANSWERED",
                     f"status={payload.get('status')!r}")
        validate_citations(checks, label, payload, admin_token, expect_document=TRAVEL_DOC_ID)
        header_trace = response.headers.get("X-Trace-Id")
        if header_trace:
            checks.check(f"{label}: traceId echoes the X-Trace-Id header",
                         str(payload.get("traceId")) == header_trace,
                         f"body={payload.get('traceId')!r} header={header_trace!r}")
    diagnostics["singleTurn"] = citation_summary(payload if isinstance(payload, dict) else {})
    return payload if isinstance(payload, dict) else {}


def check_history_resolution(checks, admin_token, turn1_payload, diagnostics):
    history = [{"question": TRAVEL_QUESTION, "answer": turn1_payload.get("answer", "")}]

    without_label = "follow-up WITHOUT history " + FOLLOW_UP_QUESTION
    attempts = []
    response = ask(admin_token, {"question": FOLLOW_UP_QUESTION}, without_label)
    attempts.append({"status": response.status, "payload": citation_summary(response.payload or {})})
    allowed = (response.status == 200 and isinstance(response.payload, dict)
               and (response.payload.get("status") == "INSUFFICIENT_EVIDENCE"
                    or (response.payload.get("status") == "ANSWERED"
                        and travel_citation_count(response.payload) == 0)))
    if not allowed:
        retry_label = without_label + " (retry)"
        retry = ask(admin_token, {"question": FOLLOW_UP_QUESTION}, retry_label)
        attempts.append({"status": retry.status, "payload": citation_summary(retry.payload or {})})
        response = retry
    checks.check(f"{without_label}: HTTP 200", response.status == 200, describe(response))
    payload_without = answer_payload(checks, without_label, response) if response.status == 200 else {}
    if response.status == 200 and isinstance(payload_without, dict) and payload_without:
        status = payload_without.get("status")
        travel_citations = travel_citation_count(payload_without)
        branch = None
        if status == "INSUFFICIENT_EVIDENCE":
            branch = "INSUFFICIENT_EVIDENCE"
        elif status == "ANSWERED" and travel_citations == 0:
            branch = "ANSWERED without any citation from 差旅与费用报销标准"
        if branch:
            checks.check(f"{without_label}: documented branch observed ({branch})", True)
        else:
            checks.check(f"{without_label}: documented branch observed", False,
                         f"status={status!r} travelCitations={travel_citations}; "
                         f"payload={json.dumps(payload_without, ensure_ascii=False)[:600]}")
            checks.deviation(
                f"{without_label} unexpectedly answered from {TRAVEL_TITLE} without history "
                f"(attempts={json.dumps(attempts, ensure_ascii=False)[:900]}); the deployed "
                "ai-service uses the real chat endpoint (RAG_CHAT_URL in .env), so retrieval/model "
                "behaviour is environment dependent. History-based answer is still verified separately.")
        if status == "ANSWERED" and payload_without.get("citations"):
            validate_citations(checks, without_label, payload_without, admin_token)

    with_label = "follow-up WITH history " + FOLLOW_UP_QUESTION
    history_response = ask(admin_token, {"question": FOLLOW_UP_QUESTION, "history": history}, with_label)
    payload_with = answer_payload(checks, with_label, history_response) if history_response.status == 200 else {}
    if history_response.status == 200 and isinstance(payload_with, dict) and payload_with:
        checks.check(f"{with_label}: status is ANSWERED", payload_with.get("status") == "ANSWERED",
                     f"status={payload_with.get('status')!r}")
        checks.check(f"{with_label}: at least one citation from {TRAVEL_TITLE}",
                     travel_citation_count(payload_with) >= 1,
                     f"titles={[c.get('title') for c in payload_with.get('citations') or [] if isinstance(c, dict)]}")
        validate_citations(checks, with_label, payload_with, admin_token, expect_document=TRAVEL_DOC_ID)
        scope_cited = any(isinstance(citation.get("quote"), str)
                          and ("本文件适用于" in citation["quote"] or "适用范围" in citation["quote"])
                          for citation in payload_with.get("citations") or [] if isinstance(citation, dict))
        checks.check(f"{with_label}: history resolution surfaces the 适用范围 paragraph", scope_cited,
                     f"quotes={[c.get('quote') for c in payload_with.get('citations') or []]}")
        without_payload = response.payload if isinstance(response.payload, dict) else {}
        with_travel = travel_citation_count(payload_with)
        without_travel = travel_citation_count(without_payload)
        strictly_better = (
            payload_with.get("status") == "ANSWERED"
            and with_travel >= 1
            and (without_payload.get("status") != "ANSWERED" or without_travel < with_travel)
        )
        checks.check(f"{with_label}: history-based answer is strictly better than without history",
                     strictly_better,
                     f"with={{status={payload_with.get('status')!r}, travelCitations={with_travel}}}, "
                     f"without={{status={without_payload.get('status')!r}, travelCitations={without_travel}}}")
    diagnostics["historyWithout"] = {
        "attempts": attempts,
        "status": (response.payload or {}).get("status"),
        "travelCitations": travel_citation_count(response.payload or {}),
    }
    diagnostics["historyWith"] = {
        "status": (history_response.payload or {}).get("status"),
        "travelCitations": travel_citation_count(history_response.payload or {}),
        "citations": citation_summary(history_response.payload or {}).get("citations"),
    }
    return payload_with if isinstance(payload_with, dict) else {}, attempts


def check_history_never_evidence(checks, admin_token, turn1_payload, diagnostics):
    label = "history must not act as evidence"
    fabricated = (turn1_payload.get("answer", "") +
                  f" 另据内网差旅专区指引（{HISTORY_MARKER}），住宿发票须在 30 天内提交财务共享中心审核。")
    history = [{"question": "差旅报销凭证由谁审核？", "answer": fabricated}]
    response = ask(admin_token, {"question": FOLLOW_UP_QUESTION, "history": history}, label)
    checks.check(f"{label}: no server error (HTTP {response.status})", response.status < 500, describe(response))
    payload = answer_payload(checks, label, response) if response.status == 200 else {}
    if response.status == 200 and isinstance(payload, dict) and payload:
        if payload.get("status") == "ANSWERED":
            validate_citations(checks, label, payload, admin_token)
            quotes = [citation.get("quote") for citation in payload.get("citations") or []
                      if isinstance(citation, dict)]
            checks.check(f"{label}: no citation quotes the fabricated history marker", not any(
                isinstance(quote, str) and HISTORY_MARKER in quote for quote in quotes), f"quotes={quotes}")
        checks.check(f"{label}: answer never leaks the fabricated history marker",
                     HISTORY_MARKER not in str(payload.get("answer", "")),
                     f"answer={payload.get('answer')!r}")
    diagnostics["historyNeverEvidence"] = {
        "httpStatus": response.status,
        "status": payload.get("status"),
        "answer": payload.get("answer"),
        "citations": citation_summary(payload).get("citations"),
        "historyAnswer": fabricated,
    }


def check_history_caps(checks, admin_token, turn1_payload, diagnostics):
    base_answer = turn1_payload.get("answer", "")

    ten_turns = [{"question": TRAVEL_QUESTION, "answer": base_answer} for _ in range(10)]
    four_turns = ten_turns[:4]
    label_ten = "history caps: 10 synthetic turns"
    ten_response = ask(admin_token, {"question": TRAVEL_QUESTION, "history": ten_turns}, label_ten)
    ten_payload = answer_payload(checks, label_ten, ten_response) if ten_response.status == 200 else {}
    if ten_response.status == 200 and isinstance(ten_payload, dict) and ten_payload.get("status") == "ANSWERED":
        validate_citations(checks, label_ten, ten_payload, admin_token, expect_document=TRAVEL_DOC_ID)
    label_four = "history caps: 4-turn baseline"
    four_response = ask(admin_token, {"question": TRAVEL_QUESTION, "history": four_turns}, label_four)
    four_payload = answer_payload(checks, label_four, four_response) if four_response.status == 200 else {}
    checks.check(f"{label_ten}: behaves like the 4-turn window (same status)",
                 ten_payload.get("status") == four_payload.get("status"),
                 f"10-turn={ten_payload.get('status')!r} 4-turn={four_payload.get('status')!r} "
                 "(real chat model, wording may differ)")

    label_long = "history caps: 5000-char history answer"
    long_history = [{"question": TRAVEL_QUESTION, "answer": (base_answer * 60)[:5000]}]
    long_response = ask(admin_token, {"question": TRAVEL_QUESTION, "history": long_history}, label_long)
    long_payload = answer_payload(checks, label_long, long_response) if long_response.status == 200 else {}
    if long_response.status == 200 and isinstance(long_payload, dict) and long_payload.get("status") == "ANSWERED":
        validate_citations(checks, label_long, long_payload, admin_token, expect_document=TRAVEL_DOC_ID)

    diagnostics["caps"] = {
        "tenTurns": {"status": ten_payload.get("status"), "citations": len(ten_payload.get("citations") or [])},
        "fourTurns": {"status": four_payload.get("status"), "citations": len(four_payload.get("citations") or [])},
        "longAnswer": {"status": long_payload.get("status"),
                       "citations": len(long_payload.get("citations") or [])},
    }


def check_non_array_history(checks, admin_token, baseline_payload, diagnostics):
    observations = []
    for value in ({}, "oops", [1, 2]):
        label = f"non-array history {json.dumps(value, ensure_ascii=False)}"
        response = ask(admin_token, {"question": TRAVEL_QUESTION, "history": value}, label)
        payload = answer_payload(checks, label, response) if response.status == 200 else {}
        if response.status == 200 and isinstance(payload, dict) and payload.get("status") == "ANSWERED":
            validate_citations(checks, label, payload, admin_token)
        observations.append({"historyValue": value, "httpStatus": response.status,
                             "status": payload.get("status"),
                             "citations": len(payload.get("citations") or [])})
        checks.check(f"{label}: behaves like a single turn (status matches baseline)",
                     payload.get("status") == baseline_payload.get("status"),
                     f"observed={payload.get('status')!r} baseline={baseline_payload.get('status')!r}")
    diagnostics["nonArrayHistory"] = observations


def check_validation(checks, admin_token, employee_token):
    label = "validation: empty question"
    envelope_check(checks, label, ask(admin_token, {"question": ""}, label), 400, "BAD_REQUEST")
    label = "validation: whitespace-only question"
    envelope_check(checks, label, ask(admin_token, {"question": "   "}, label), 400, "BAD_REQUEST")
    label = "validation: 1001-char question"
    envelope_check(checks, label, ask(admin_token, {"question": "问" * (MAX_QUESTION_LENGTH + 1)}, label),
                   400, "BAD_REQUEST")
    label = "validation: malformed JSON body"
    envelope_check(checks, label,
                   http("POST", "/api/v1/questions", token=admin_token, raw=b"{this is not json"),
                   400, "BAD_REQUEST")

    label = "access: anonymous question with history"
    response = http("POST", "/api/v1/questions", body={"question": FOLLOW_UP_QUESTION,
                                                       "history": [{"question": TRAVEL_QUESTION, "answer": "x"}]})
    envelope_check(checks, label, response, 401, "UNAUTHORIZED")

    label = "access: tenant-a EMPLOYEE asks with history"
    response = ask(employee_token, {"question": FOLLOW_UP_QUESTION,
                                    "history": [{"question": TRAVEL_QUESTION,
                                                 "answer": "一线城市出差住宿标准为每晚不超过 600 元。"}]}, label)
    payload = answer_payload(checks, label, response) if response.status == 200 else {}
    if response.status == 200 and isinstance(payload, dict) and payload.get("status") == "ANSWERED":
        validate_citations(checks, label, payload, employee_token)
    return payload if isinstance(payload, dict) else {}


def check_regressions(checks, diagnostics):
    identity_label = "regression: identity"
    identity = check_identity(checks, identity_label, http("GET", "/api/v1/identity", token=ADMIN_TOKEN))
    checks.check(f"{identity_label}: tenant-a ADMIN", identity.get("tenantId") == TENANT
                 and "ADMIN" in (identity.get("roles") or []),
                 json.dumps(identity, ensure_ascii=False)[:300])

    usage_label = "regression: usage"
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    usage = http("GET", f"/api/v1/usage?date={today}", token=ADMIN_TOKEN)
    checks.check(f"{usage_label}: HTTP 200", usage.status == 200, describe(usage))
    payload = usage.payload if isinstance(usage.payload, dict) else {}
    checks.check(f"{usage_label}: exposes date/currency/models",
                 payload.get("date") == today and isinstance(payload.get("currency"), str)
                 and isinstance(payload.get("models"), list),
                 json.dumps(payload, ensure_ascii=False)[:300])
    diagnostics["usage"] = {"date": payload.get("date"), "currency": payload.get("currency"),
                            "models": len(payload.get("models") or [])}

    regression = {}
    for script_name, minimum_passed, description in REGRESSION_TARGETS:
        regression[script_name] = run_regression_script(checks, script_name, minimum_passed, description)
    diagnostics["regression"] = regression
    return regression


def run_regression_script(checks, script_name, minimum_passed, description):
    path = ROOT / "scripts" / script_name
    label = f"regression: {script_name} ({description})"
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    attempts = []
    process = None
    output = ""
    for attempt in range(2):
        process = subprocess.run(
            [sys.executable, str(path)],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=600,
            env=environment,
        )
        output = (process.stdout or "") + (process.stderr or "")
        match = re.search(r"SUMMARY\s+checks=(\d+)\s+passed=(\d+)\s+failed=(\d+)", output)
        checks_count = int(match.group(1)) if match else None
        passed = int(match.group(2)) if match else None
        failed = int(match.group(3)) if match else None
        attempts.append({"exitCode": process.returncode, "checks": checks_count,
                         "passed": passed, "failed": failed})
        if process.returncode == 0 and failed == 0:
            break
        if "QUOTA_EXCEEDED" in output and attempt == 0:
            print(f"WAIT  {script_name} hit the question quota, retrying after the rate window resets", flush=True)
            time.sleep(65)
            continue
        break
    last = attempts[-1]
    checks.check(f"{label}: exit code 0", last["exitCode"] == 0, f"exitCode={last['exitCode']} tail={output[-600:]}")
    checks.check(f"{label}: reported failed=0", last["failed"] == 0, f"failed={last['failed']}")
    checks.check(f"{label}: passed >= {minimum_passed}", last["passed"] is not None
                 and last["passed"] >= minimum_passed, f"passed={last['passed']} checks={last['checks']}")
    print(f"INFO  {script_name} output tail: {output.strip().splitlines()[-1] if output.strip() else '<empty>'}", flush=True)
    return {"attempts": attempts, "summaryLine": (output.strip().splitlines() or [""])[-1]}


def wait_for_quiet_question_window():
    """The gateway enforces 12 questions/minute/tenant; clear the window before regression runs."""
    now = time.time()
    if len([entry for entry in QUESTION_LOG if entry.get("status") != 401]) >= 8:
        wait = 62 - (now % 60)
        if wait > 0:
            print(f"WAIT  sleeping {wait:.0f}s so the per-tenant question quota window resets", flush=True)
            time.sleep(wait)


def write_report(report):
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    started = time.time()
    global ADMIN_TOKEN
    checks = Checks()
    diagnostics = {}
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "gateway": GATEWAY,
        "script": "scripts/multiturn_smoke.py",
        "tenant": TENANT,
        "checks": [],
        "summary": {},
        "questionCalls": QUESTION_LOG,
        "rateLimitWaits": RATE_LIMIT_WAITS,
        "diagnostics": diagnostics,
        "deviations": checks.deviations,
        "notes": [
            "Documents are read-only for this script: it only asks questions and reads MySQL for chunk text.",
            "The deployed ai-service points RAG_CHAT_URL at the real chat endpoint (see .env), so answer "
            "wording is nondeterministic; assertions target the service contract and citation grounding.",
        ],
        "fatalError": None,
    }
    fatal = None
    try:
        metadata = get_metadata()
        checks.check("OIDC discovery is reachable", metadata.get("issuer") == ISSUER, metadata.get("issuer"))
        admin = login(metadata, "a-admin")
        employee = login(metadata, "a-employee")
        ADMIN_TOKEN = admin["access_token"]
        employee_token = employee["access_token"]
        checks.check("a-admin obtains an access token", bool(ADMIN_TOKEN))
        checks.check("a-employee obtains an access token", bool(employee_token))

        check_identity(checks, "identity: a-admin", http("GET", "/api/v1/identity", token=ADMIN_TOKEN))
        check_identity(checks, "identity: a-employee", http("GET", "/api/v1/identity", token=employee_token))

        check_seed_documents(checks, ADMIN_TOKEN)

        turn1 = check_single_turn(checks, ADMIN_TOKEN, diagnostics)
        check_history_resolution(checks, ADMIN_TOKEN, turn1, diagnostics)
        check_history_never_evidence(checks, ADMIN_TOKEN, turn1, diagnostics)
        check_history_caps(checks, ADMIN_TOKEN, turn1, diagnostics)
        check_non_array_history(checks, ADMIN_TOKEN, turn1, diagnostics)
        check_validation(checks, ADMIN_TOKEN, employee_token)

        wait_for_quiet_question_window()
        check_regressions(checks, diagnostics)
    except Exception as error:  # noqa: BLE001 - fatal setup failure must still write a report
        fatal = f"{type(error).__name__}: {error}"
        checks.add("script completed without a fatal error", False, fatal)
        report["fatalError"] = fatal

    duration = time.time() - started
    report["checks"] = checks.items
    report["summary"] = checks.summary(duration)
    report["durationSeconds"] = round(duration, 1)
    report["deviations"] = checks.deviations
    report["rateLimitWaits"] = RATE_LIMIT_WAITS
    write_report(report)
    summary = report["summary"]
    print(f"SUMMARY  checks={summary['checks']} passed={summary['passed']} failed={summary['failed']} "
          f"duration={summary['durationSeconds']}s", flush=True)
    print(f"REPORT   {REPORT_PATH}", flush=True)
    return 0 if summary["failed"] == 0 and fatal is None else 1


ADMIN_TOKEN = ""


if __name__ == "__main__":
    sys.exit(main())
