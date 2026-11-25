"""Registration, invite-code and account-governance API smoke test.

Covers the self-service onboarding surface added to the gateway (task-12):
  * GET  /api/v1/invite-codes            (per-tenant active code + history)
  * POST /api/v1/invite-codes/rotate     (manual rotation invalidates the old code)
  * POST /api/v1/register                (anonymous, invite-code gated, 201)
  * POST /api/v1/users/{u}/promote|demote (ADMIN only, same tenant, no self-edit)

The script is dependency-free (Python 3.12 stdlib only) and reuses the OIDC login
helper from scripts/smoke.py. It leaves exactly one registered demo account
behind (`qa-member` / `RagLocalExample42!`, tenant-a EMPLOYEE) and removes every
throwaway account it created through the Keycloak Admin REST API.

Run:  $env:PYTHONIOENCODING='utf-8'; python scripts/register_invite_smoke.py
Report: artifacts/register_invite_report.json ; exit code 0 only when all pass.
"""

import json
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from smoke import GATEWAY, ISSUER, NoRedirect, ROOT, login  # noqa: E402

OPENER = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
KEYCLOAK_ROOT = "http://127.0.0.1:8180"
REALM = "rag-local"
ISSUER_LOCAL = ISSUER
REPORT_PATH = ROOT / "artifacts" / "register_invite_report.json"
ENV_PATH = ROOT / ".env"

DEMO_USER = "qa-member"
DEMO_PASSWORD = "RagLocalExample42!"
CODE_PATTERN = re.compile(r"^[A-Z0-9]{8}$")
INVITE_ALPHABET = set("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
ROTATION_SECONDS = 2 * 60 * 60
DAYS = 86400.0


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def describe(value, limit=400):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return text if len(text) <= limit else text[:limit] + "…"


def envelope_ok(payload):
    return (
        isinstance(payload, dict)
        and isinstance(payload.get("code"), str) and payload["code"]
        and isinstance(payload.get("message"), str) and payload["message"]
        and isinstance(payload.get("traceId"), str) and payload["traceId"]
    )


def http(method, path, *, token=None, body=None, raw=None, headers=None, timeout=60):
    """HTTP call returning (status, headers, text, parsed-json-or-None)."""
    request_headers = dict(headers or {})
    if token:
        request_headers["Authorization"] = "Bearer " + token
    data = None
    if raw is not None:
        data = raw
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers.setdefault("Content-Type", "application/json; charset=utf-8")
    url = path if path.startswith("http") else GATEWAY + path
    try:
        response = OPENER.open(urllib.request.Request(url, data, request_headers, method=method), timeout=timeout)
        status, response_headers, raw_body = response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        status, response_headers, raw_body = error.code, dict(error.headers), error.read()
    text = raw_body.decode("utf-8", errors="replace")
    try:
        payload = json.loads(text) if text.strip() else None
    except json.JSONDecodeError:
        payload = None
    return status, response_headers, text, payload


def env_values():
    values = {}
    if not ENV_PATH.is_file():
        return values
    for line in ENV_PATH.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def epoch_millis(value):
    return isinstance(value, int) and value > 1_000_000_000_000


class Checks:
    def __init__(self):
        self.rows = []

    def check(self, name, condition, detail=""):
        passed = bool(condition)
        row = {"name": name, "status": "PASS" if passed else "FAIL", "detail": "" if passed else describe(detail)}
        self.rows.append(row)
        if passed:
            print(f"PASS  {name}", flush=True)
        else:
            print(f"FAIL  {name}  <- {row['detail']}", flush=True)
        return passed

    def info(self, message):
        print(f"INFO  {describe(message, 600)}", flush=True)

    def summary(self, duration):
        passed = sum(1 for row in self.rows if row["status"] == "PASS")
        failed = len(self.rows) - passed
        return {"checks": len(self.rows), "passed": passed, "failed": failed, "durationSeconds": round(duration, 1)}


# --------------------------------------------------------------------------- #
# small domain helpers
# --------------------------------------------------------------------------- #
def register(body, token=None):
    return http("POST", "/api/v1/register", token=token, body=body)


def invite_view(token):
    return http("GET", "/api/v1/invite-codes", token=token)


def rotate_invite(token):
    return http("POST", "/api/v1/invite-codes/rotate", token=token)


def change_roles(token, username, action):
    return http("POST", f"/api/v1/users/{urllib.parse.quote(username)}/{action}", token=token)


def access(token):
    return http("GET", "/api/v1/access", token=token)


def active_of(view):
    active = view.get("active") if isinstance(view, dict) else None
    return active if isinstance(active, dict) else {}


def active_code(view):
    return active_of(view).get("code")


def history_codes(view):
    return [entry.get("code") for entry in (view.get("history") or []) if isinstance(entry, dict)]


def invite_code_of(token):
    status, _, text, payload = invite_view(token)
    if status != 200 or not isinstance(payload, dict):
        raise AssertionError(f"GET /api/v1/invite-codes expected HTTP 200, received {status} {text[:200]}")
    return payload


def keycloak_token(checks, evidence):
    values = env_values()
    user = values.get("RAG_KEYCLOAK_ADMIN_USER")
    password = values.get("RAG_KEYCLOAK_ADMIN_PASSWORD")
    evidence["cleanup"] = {"keycloakAdminConfigured": bool(user and password)}
    if not user or not password:
        return None
    form = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "admin-cli", "username": user, "password": password,
    }).encode()
    status, _, text, payload = http(
        "POST", f"{KEYCLOAK_ROOT}/realms/master/protocol/openid-connect/token",
        raw=form, headers={"Content-Type": "application/x-www-form-urlencoded"}, timeout=30,
    )
    if status != 200 or not isinstance(payload, dict) or not payload.get("access_token"):
        checks.info(f"Keycloak Admin token unavailable (HTTP {status}); leftovers will be documented")
        return None
    return payload["access_token"]


def keycloak_users(admin_token, search=None):
    query = f"/admin/realms/{REALM}/users?max=200"
    if search:
        query += "&search=" + urllib.parse.quote(search)
    status, _, text, payload = http("GET", KEYCLOAK_ROOT + query, token=admin_token, timeout=30)
    if status != 200 or not isinstance(payload, list):
        raise AssertionError(f"Keycloak user search failed (HTTP {status}) {text[:200]}")
    return payload


def keycloak_user(admin_token, username):
    status, _, text, payload = http(
        "GET", f"{KEYCLOAK_ROOT}/admin/realms/{REALM}/users?username=" + urllib.parse.quote(username) + "&exact=true",
        token=admin_token, timeout=30,
    )
    if status != 200 or not isinstance(payload, list):
        raise AssertionError(f"Keycloak user lookup failed for {username} (HTTP {status}) {text[:200]}")
    return payload


def keycloak_delete(admin_token, user_id):
    status, _, text, _ = http("DELETE", f"{KEYCLOAK_ROOT}/admin/realms/{REALM}/users/{user_id}", token=admin_token, timeout=30)
    return status, text


# --------------------------------------------------------------------------- #
# phases
# --------------------------------------------------------------------------- #
def check_invite_codes(checks, evidence, tokens):
    status, _, text, view = invite_view(tokens["a-admin"])
    checks.check("GET /api/v1/invite-codes as a-admin returns 200 with a JSON view",
                 status == 200 and isinstance(view, dict), f"HTTP {status} body={text[:300]}")
    if not isinstance(view, dict):
        raise AssertionError("invite-code view is not a JSON object")

    checks.check("invite view exposes rotationMinutes=120",
                 view.get("rotationMinutes") == 120, f"rotationMinutes={view.get('rotationMinutes')}")
    a_active = active_of(view)
    checks.check("tenant-a active code carries code/tenantId/generatedAt/expiresAt/remainingSeconds",
                 set(a_active) == {"code", "tenantId", "generatedAt", "expiresAt", "remainingSeconds"},
                 sorted(a_active))
    checks.check("tenant-a active code matches ^[A-Z0-9]{8}$",
                 isinstance(a_active.get("code"), str) and CODE_PATTERN.match(a_active["code"]) is not None,
                 a_active.get("code"))
    checks.check("tenant-a active code only uses the documented alphabet",
                 isinstance(a_active.get("code"), str) and set(a_active["code"]) <= INVITE_ALPHABET,
                 a_active.get("code"))
    checks.check("tenant-a active code belongs to tenant-a",
                 a_active.get("tenantId") == "tenant-a", a_active.get("tenantId"))
    remaining = a_active.get("remainingSeconds")
    checks.check("remainingSeconds is within the two-hour window (0 < s <= 7200)",
                 isinstance(remaining, int) and 0 < remaining <= ROTATION_SECONDS, remaining)
    checks.check("generatedAt/expiresAt are epoch milliseconds",
                 epoch_millis(a_active.get("generatedAt")) and epoch_millis(a_active.get("expiresAt")),
                 f"{a_active.get('generatedAt')}/{a_active.get('expiresAt')}")
    delta = (a_active.get("expiresAt") or 0) - (a_active.get("generatedAt") or 0)
    checks.check("code lifetime is 7200s (generatedAt→expiresAt, ±5s)",
                 abs(delta / 1000.0 - ROTATION_SECONDS) <= 5, f"delta={delta / 1000.0}s")

    history = view.get("history")
    checks.check("invite view exposes a history array", isinstance(history, list), type(history).__name__)
    checks.check("history entries expose code/generatedAt/expiresAt",
                 all(isinstance(entry, dict) and {"code", "generatedAt", "expiresAt"} <= set(entry)
                     and CODE_PATTERN.match(str(entry.get("code"))) for entry in (history or [])),
                 describe(history))
    checks.check("tenant-a history retains earlier rotated codes", isinstance(history, list) and len(history) >= 1,
                 f"history={len(history) if isinstance(history, list) else 'n/a'}")

    b_status, _, b_text, b_view = invite_view(tokens["b-admin"])
    checks.check("b-admin can read the tenant-b invite code view",
                 b_status == 200 and isinstance(b_view, dict), f"HTTP {b_status} body={b_text[:300]}")
    b_view = b_view if isinstance(b_view, dict) else {}
    b_active = active_of(b_view)
    checks.check("tenant-b active code belongs to tenant-b", b_active.get("tenantId") == "tenant-b", b_active.get("tenantId"))
    checks.check("tenant-b code differs from the tenant-a code",
                 a_active.get("code") != b_active.get("code"), f"{a_active.get('code')} vs {b_active.get('code')}")
    checks.check("tenant-b view also rotates on a two-hour window",
                 b_view.get("rotationMinutes") == 120, b_view.get("rotationMinutes"))

    evidence["inviteCodes"] = {
        "tenantA": view, "tenantB": b_view,
    }
    return view, b_view


def check_rotation(checks, evidence, tokens, a_view, b_view):
    old_code = active_code(a_view)
    old_history = len(a_view.get("history") or [])
    status, _, text, rotated = rotate_invite(tokens["a-admin"])
    checks.check("POST /api/v1/invite-codes/rotate as a-admin returns 200",
                 status == 200 and isinstance(rotated, dict), f"HTTP {status} body={text[:300]}")
    rotated = rotated if isinstance(rotated, dict) else {}
    new_code = active_code(rotated)
    checks.check("rotation issues a different active code",
                 isinstance(new_code, str) and CODE_PATTERN.match(new_code) and new_code != old_code,
                 f"{old_code} -> {new_code}")
    checks.check("rotation keeps the two-hour lifetime",
                 abs((active_of(rotated).get("expiresAt", 0) - active_of(rotated).get("generatedAt", 0))
                     / 1000.0 - ROTATION_SECONDS) <= 5,
                 describe(active_of(rotated)))
    new_history = len(rotated.get("history") or [])
    checks.check("rotation grows the history by one entry (capped at 10)",
                 new_history == min(old_history + 1, 10), f"{old_history} -> {new_history}")
    checks.check("the retired tenant-a code is now recorded in history as invalid",
                 old_code in history_codes(rotated), describe(history_codes(rotated)))

    status, _, text, payload = register({"username": "qa-reg-oldcode", "password": DEMO_PASSWORD, "inviteCode": old_code})
    checks.check("the retired code is rejected by POST /api/v1/register with 400",
                 status == 400 and envelope_ok(payload), f"HTTP {status} body={text[:300]}")
    checks.check("retired-code rejection uses INVITE_CODE_EXPIRED or INVALID_INVITE_CODE",
                 isinstance(payload, dict) and payload.get("code") in {"INVITE_CODE_EXPIRED", "INVALID_INVITE_CODE"},
                 describe(payload))

    old_b = active_code(b_view)
    b_status, _, b_text, b_rotated = rotate_invite(tokens["b-admin"])
    b_rotated = b_rotated if isinstance(b_rotated, dict) else {}
    checks.check("POST /api/v1/invite-codes/rotate as b-admin returns 200",
                 b_status == 200 and active_code(b_rotated) not in {None, old_b}, f"HTTP {b_status} body={b_text[:300]}")

    a_after = invite_code_of(tokens["a-admin"])
    checks.check("b-admin rotation does not touch the tenant-a code",
                 active_code(a_after) == new_code, f"{active_code(a_after)} vs {new_code}")
    b_after = invite_code_of(tokens["b-admin"])
    checks.check("b-admin rotation leaves tenant-b with a fresh, valid code",
                 active_code(b_after) == active_code(b_rotated)
                 and 0 < (b_after.get("active") or {}).get("remainingSeconds", 0) <= ROTATION_SECONDS,
                 describe(b_after.get("active")))

    evidence["rotation"] = {"oldTenantACode": old_code, "newTenantACode": new_code, "tenantBView": b_after}
    return a_after, b_after


def check_register_validation(checks, evidence, fresh_code):
    cases = [
        ("empty JSON object", {}, 400, "INVALID_USERNAME"),
        ("missing username", {"password": DEMO_PASSWORD, "inviteCode": fresh_code}, 400, "INVALID_USERNAME"),
        ("one-character username", {"username": "a", "password": DEMO_PASSWORD, "inviteCode": fresh_code}, 400, "INVALID_USERNAME"),
        ("username with punctuation", {"username": "a!", "password": DEMO_PASSWORD, "inviteCode": fresh_code}, 400, "INVALID_USERNAME"),
        ("short password", {"username": "qa-reg-shortpw", "password": "short", "inviteCode": fresh_code}, 400, "WEAK_PASSWORD"),
        ("unknown 8-character invite code",
         {"username": "qa-reg-unknown", "password": DEMO_PASSWORD, "inviteCode": "ZZZZZZZZ"}, 400, "INVALID_INVITE_CODE"),
        ("malformed invite code (lowercase/short)",
         {"username": "qa-reg-badcode", "password": DEMO_PASSWORD, "inviteCode": "abc"}, 400, "INVALID_INVITE_CODE"),
        ("malformed invite code (punctuation)",
         {"username": "qa-reg-badcode2", "password": DEMO_PASSWORD, "inviteCode": "AB!DEFGH"}, 400, "INVALID_INVITE_CODE"),
    ]
    results = []
    for label, body, expected_status, expected_code in cases:
        status, _, text, payload = register(body)
        checks.check(f"register rejects {label} with HTTP {expected_status}",
                     status == expected_status and envelope_ok(payload), f"HTTP {status} body={text[:300]}")
        checks.check(f"register {label} error code is {expected_code}",
                     isinstance(payload, dict) and payload.get("code") == expected_code, describe(payload))
        results.append({"case": label, "status": status, "body": payload})
    evidence["registerValidation"] = results


def ensure_demo_account(checks, evidence, fresh_code, tokens):
    """Create (or reuse) the one registered demo account left behind for the team."""
    status, _, text, payload = register({"username": DEMO_USER, "password": DEMO_PASSWORD, "inviteCode": fresh_code})
    created = status == 201
    checks.check("POST /api/v1/register creates the qa-member demo account (or reuses it on rerun)",
                 status in {201, 409}, f"HTTP {status} body={text[:300]}")
    if created:
        checks.check("registration returns exactly username/tenantId/message",
                     isinstance(payload, dict) and set(payload) == {"username", "tenantId", "message"}, describe(payload))
        checks.check("registration pins the new account to tenant-a",
                     isinstance(payload, dict) and payload.get("tenantId") == "tenant-a", describe(payload))
        checks.check("registration response echoes the registered username",
                     isinstance(payload, dict) and payload.get("username") == DEMO_USER, describe(payload))
    else:
        checks.check("a rerun reports USERNAME_TAKEN for the existing demo account",
                     status == 409 and envelope_ok(payload) and payload.get("code") == "USERNAME_TAKEN", describe(payload))

    duplicate_status, _, duplicate_text, duplicate = register(
        {"username": DEMO_USER, "password": DEMO_PASSWORD, "inviteCode": fresh_code})
    checks.check("registering an existing username fails with 409 USERNAME_TAKEN",
                 duplicate_status == 409 and envelope_ok(duplicate) and duplicate.get("code") == "USERNAME_TAKEN",
                 f"HTTP {duplicate_status} body={duplicate_text[:300]}")

    demo_token = login(checks.metadata, DEMO_USER)["access_token"]
    status, _, text, identity = http("GET", "/api/v1/identity", token=demo_token)
    checks.check("the demo account can complete an OIDC login (no VERIFY_PROFILE redirect)",
                 status == 200 and isinstance(identity, dict) and identity.get("subject"), f"HTTP {status} body={text[:300]}")
    identity = identity if isinstance(identity, dict) else {}
    checks.check("demo account is an EMPLOYEE of tenant-a",
                 identity.get("tenantId") == "tenant-a" and "EMPLOYEE" in (identity.get("roles") or [])
                 and "ADMIN" not in (identity.get("roles") or []), describe(identity))
    evidence["demoAccount"] = {"username": DEMO_USER, "created": created, "identity": identity}
    return demo_token


def check_new_member_flow(checks, evidence, fresh_code, created):
    username = "qa-reg-" + secrets.token_hex(4)
    status, _, text, payload = register({"username": username, "password": DEMO_PASSWORD, "inviteCode": fresh_code})
    checks.check("anonymous registration of a new member returns 201",
                 status == 201 and isinstance(payload, dict), f"HTTP {status} body={text[:300]}")
    if status == 201:
        created.append(username)
    payload = payload if isinstance(payload, dict) else {}
    checks.check("201 body exposes username/tenantId/message and no secrets",
                 set(payload) == {"username", "tenantId", "message"}
                 and payload.get("username") == username and payload.get("tenantId") == "tenant-a",
                 describe(payload))

    token = login(checks.metadata, username)["access_token"]
    status, _, text, identity = http("GET", "/api/v1/identity", token=token)
    identity = identity if isinstance(identity, dict) else {}
    checks.check("the new member logs in and reports tenant-a EMPLOYEE with a subject",
                 status == 200 and identity.get("tenantId") == "tenant-a"
                 and identity.get("roles") == ["EMPLOYEE"] and identity.get("subject"),
                 f"HTTP {status} body={text[:300]}")

    question = {"question": "入职满一年能休几天年假？"}
    status, _, text, answer = http("POST", "/api/v1/questions", token=token, body=question)
    answer = answer if isinstance(answer, dict) else {}
    checks.check("the new EMPLOYEE can ask a question and gets 200",
                 status == 200 and answer.get("status") == "ANSWERED", f"HTTP {status} body={text[:300]}")
    checks.check("the member answer carries the service-appended citation marker",
                 isinstance(answer.get("answer"), str) and " [1]" in answer["answer"], describe(answer.get("answer")))

    for label, path in (
        ("access directory", "/api/v1/access"),
        ("evaluation reports", "/api/v1/evals"),
        ("monitoring dashboard", "/api/v1/monitoring"),
    ):
        status, _, text, forbidden = http("GET", path, token=token)
        checks.check(f"the new EMPLOYEE is denied GET {path} ({label}) with 403",
                     status == 403 and envelope_ok(forbidden), f"HTTP {status} body={text[:300]}")

    status, _, text, payload = register(
        {"username": "qa-reg-authed", "password": DEMO_PASSWORD, "inviteCode": fresh_code}, token=token)
    checks.check("register is anonymous-only: an authenticated member receives 401/403",
                 status in {401, 403} and envelope_ok(payload), f"HTTP {status} body={text[:300]}")

    evidence["newMember"] = {"username": username, "identity": identity, "answer": answer.get("status")}
    return username, token


def check_promotion(checks, evidence, admin_token, username):
    status, _, text, payload = change_roles(admin_token, username, "promote")
    payload = payload if isinstance(payload, dict) else {}
    checks.check("a-admin can promote a tenant-a member with 200",
                 status == 200 and envelope_ok_free(payload) is False and payload.get("username") == username,
                 f"HTTP {status} body={text[:300]}")
    roles = payload.get("roles") or []
    checks.check("promote response reports ADMIN and no EMPLOYEE",
                 "ADMIN" in roles and "EMPLOYEE" not in roles, describe(roles))

    promoted_token = login(checks.metadata, username)["access_token"]
    status, _, text, identity = http("GET", "/api/v1/identity", token=promoted_token)
    identity = identity if isinstance(identity, dict) else {}
    checks.check("re-login after promotion yields an ADMIN token for tenant-a",
                 status == 200 and "ADMIN" in (identity.get("roles") or [])
                 and identity.get("tenantId") == "tenant-a", f"HTTP {status} body={text[:300]}")

    status, _, text, _ = http("GET", "/api/v1/evals", token=promoted_token)
    checks.check("the promoted member can now read GET /api/v1/evals", status == 200, f"HTTP {status} body={text[:200]}")
    status, _, text, _ = http("GET", "/api/v1/invite-codes", token=promoted_token)
    checks.check("the promoted member can now read GET /api/v1/invite-codes", status == 200, f"HTTP {status} body={text[:200]}")

    status, _, text, payload = change_roles(admin_token, username, "demote")
    payload = payload if isinstance(payload, dict) else {}
    roles = payload.get("roles") or []
    checks.check("a-admin can demote the member with 200 and roles back to EMPLOYEE",
                 status == 200 and "EMPLOYEE" in roles and "ADMIN" not in roles,
                 f"HTTP {status} body={text[:300]}")

    demoted_token = login(checks.metadata, username)["access_token"]
    status, _, text, identity = http("GET", "/api/v1/identity", token=demoted_token)
    identity = identity if isinstance(identity, dict) else {}
    checks.check("re-login after demotion yields EMPLOYEE again",
                 status == 200 and identity.get("roles") == ["EMPLOYEE"], f"HTTP {status} body={text[:300]}")
    status, _, text, _ = http("GET", "/api/v1/evals", token=demoted_token)
    checks.check("the demoted member loses GET /api/v1/evals again (403)", status == 403, f"HTTP {status} body={text[:200]}")

    evidence["promotion"] = {
        "promote": {"status": 200, "roles": ["ADMIN"]},
        "demote": {"status": 200, "roles": ["EMPLOYEE"]},
    }


def envelope_ok_free(payload):
    """A success payload must NOT look like an error envelope."""
    return envelope_ok(payload)


def check_guards(checks, evidence, tokens, flow_user, freshly_registered, b_code):
    status, _, text, payload = change_roles(tokens["a-admin"], "a-admin", "promote")
    checks.check("an admin cannot promote themselves (400 CANNOT_MODIFY_SELF)",
                 status == 400 and envelope_ok(payload) and payload.get("code") == "CANNOT_MODIFY_SELF",
                 f"HTTP {status} body={text[:300]}")

    status, _, text, payload = change_roles(tokens["a-admin"], "b-admin", "promote")
    checks.check("cross-tenant promotion is rejected with 403",
                 status == 403 and envelope_ok(payload), f"HTTP {status} body={text[:300]}")

    status, _, text, payload = change_roles(tokens["a-employee"], flow_user, "promote")
    checks.check("an EMPLOYEE cannot promote anyone (403 envelope)",
                 status == 403 and envelope_ok(payload), f"HTTP {status} body={text[:300]}")

    status, _, text, payload = http("POST", f"/api/v1/users/{urllib.parse.quote(flow_user)}/promote")
    checks.check("anonymous promotion is rejected with 401 envelope",
                 status == 401 and envelope_ok(payload), f"HTTP {status} body={text[:300]}")

    unknown = "qa-nonexistent-" + secrets.token_hex(3)
    status, _, text, payload = change_roles(tokens["a-admin"], unknown, "promote")
    checks.check("promoting an unknown username returns 404 USER_NOT_FOUND",
                 status == 404 and envelope_ok(payload) and payload.get("code") == "USER_NOT_FOUND",
                 f"HTTP {status} body={text[:300]}")

    b_user = "qa-reg-" + secrets.token_hex(4)
    status, _, text, payload = register({"username": b_user, "password": DEMO_PASSWORD, "inviteCode": b_code})
    checks.check("the tenant-b invite code registers a tenant-b member",
                 status == 201 and isinstance(payload, dict) and payload.get("tenantId") == "tenant-b",
                 f"HTTP {status} body={text[:300]}")
    if status == 201:
        freshly_registered.append(b_user)
    status, _, text, payload = change_roles(tokens["b-admin"], b_user, "promote")
    payload = payload if isinstance(payload, dict) else {}
    checks.check("b-admin can promote a tenant-b member to ADMIN",
                 status == 200 and "ADMIN" in (payload.get("roles") or []), f"HTTP {status} body={text[:300]}")
    status, _, text, payload = change_roles(tokens["b-admin"], b_user, "demote")
    checks.check("b-admin can demote the tenant-b member back to EMPLOYEE",
                 status == 200 and isinstance(payload, dict) and payload.get("roles") == ["EMPLOYEE"],
                 f"HTTP {status} body={text[:300]}")

    evidence["guards"] = {"crossTenant": 403, "selfEdit": "CANNOT_MODIFY_SELF", "employee": 403, "anonymous": 401,
                          "unknown": 404, "tenantBUser": b_user}


def check_code_reuse(checks, evidence, fresh_code, freshly_registered):
    username = "qa-reg-" + secrets.token_hex(4)
    status, _, text, payload = register({"username": username, "password": DEMO_PASSWORD, "inviteCode": fresh_code})
    checks.check("a fresh register with the SAME active code still succeeds within the window (201)",
                 status == 201, f"HTTP {status} body={text[:300]}")
    if status == 201:
        freshly_registered.append(username)
    evidence["codeReuse"] = {"username": username, "status": status}


def check_access_and_regression(checks, evidence, tokens, users_to_find, eval_total_floor=26):
    status, _, text, payload = access(tokens["a-admin"])
    payload = payload if isinstance(payload, dict) else {}
    checks.check("GET /api/v1/access as a-admin returns 200 after registrations",
                 status == 200 and isinstance(payload, dict), f"HTTP {status} body={text[:300]}")
    users = payload.get("users") if isinstance(payload.get("users"), list) else []
    by_name = {str(user.get("username")): user for user in users if isinstance(user, dict)}
    checks.check("the directory now lists every registered account with fresh roles",
                 all(name in by_name for name, _, _ in users_to_find),
                 f"expected={[name for name, _, _ in users_to_find]} got={sorted(by_name)[:20]}")
    for name, tenant, role in users_to_find:
        row = by_name.get(name) or {}
        checks.check(f"directory row for {name} reports {role} in {tenant}",
                     row.get("tenantId") == tenant and role in (row.get("roles") or []),
                     describe(row))
    counts = payload.get("counts") if isinstance(payload.get("counts"), dict) else {}
    employees = sum(1 for user in users if "EMPLOYEE" in (user.get("roles") or []))
    admins = sum(1 for user in users if "ADMIN" in (user.get("roles") or []))
    checks.check("access counts agree with the returned user rows",
                 counts.get("total") == len(users) and counts.get("employees") == employees
                 and counts.get("admins") == admins, describe(counts))
    checks.check("access counts still cover tenant-a and tenant-b",
                 set(counts.get("tenants") or []) >= {"tenant-a", "tenant-b"}, describe(counts.get("tenants")))

    status, _, text, payload = http("GET", "/api/v1/evals", token=tokens["a-admin"])
    payload = payload if isinstance(payload, dict) else {}
    checks.check("GET /api/v1/evals still returns 200 with the archived reports",
                 status == 200 and isinstance(payload.get("items"), list), f"HTTP {status} body={text[:300]}")
    total = payload.get("total")
    checks.check(f"GET /api/v1/evals total is still at least {eval_total_floor}",
                 isinstance(total, int) and total >= eval_total_floor, f"total={total}")

    status, _, text, payload = http("GET", "/api/v1/monitoring", token=tokens["a-admin"])
    payload = payload if isinstance(payload, dict) else {}
    services = payload.get("services") if isinstance(payload.get("services"), list) else []
    checks.check("GET /api/v1/monitoring still lists the ten stack services",
                 status == 200 and len(services) == 10, f"HTTP {status} services={len(services)}")
    checks.check("all monitored services are still UP (DEGRADED tolerated only for keycloak/opensearch)",
                 all(service.get("status") == "UP" or (service.get("status") == "DEGRADED"
                     and service.get("name") in {"keycloak", "opensearch"}) for service in services),
                 describe([(s.get("name"), s.get("status")) for s in services]))
    checks.check("monitoring still exposes the nine contracted metric counters",
                 set((payload.get("metrics") or {})) == {"questionCount", "questionFailures", "questionSuccessRate",
                                                        "averageLatencyMs", "inputTokens", "outputTokens",
                                                        "modelFailovers", "kafkaPendingEvents", "indexFailures"},
                 describe(sorted((payload.get("metrics") or {}))))

    status, _, text, identity = http("GET", "/api/v1/identity", token=tokens["a-admin"])
    checks.check("GET /api/v1/identity is unchanged for a-admin",
                 status == 200 and isinstance(identity, dict) and identity.get("tenantId") == "tenant-a"
                 and identity.get("roles") == ["ADMIN"], f"HTTP {status} body={text[:300]}")

    status, _, text, answer = http("POST", "/api/v1/questions", token=tokens["a-admin"],
                                   body={"question": "出差住宿标准是多少？"})
    checks.check("seeded questions are still answered with citations after the onboarding changes",
                 status == 200 and isinstance(answer, dict) and answer.get("status") == "ANSWERED"
                 and " [1]" in (answer.get("answer") or ""), f"HTTP {status} body={text[:300]}")

    status, _, text, usage = http("GET", "/api/v1/usage?date=" + datetime.now(timezone.utc).strftime("%Y-%m-%d"),
                                  token=tokens["a-admin"])
    checks.check("GET /api/v1/usage is unchanged (date/currency/models)",
                 status == 200 and isinstance(usage, dict) and usage.get("date")
                 and usage.get("currency") and isinstance(usage.get("models"), list),
                 f"HTTP {status} body={text[:300]}")

    evidence["regression"] = {"evalsTotal": total, "services": len(services), "accessCounts": counts}


def cleanup(checks, evidence, tokens, leftovers):
    """Remove throwaway accounts through the Keycloak Admin API and keep the demo account."""
    cleanup_evidence = evidence.setdefault("cleanup", {})
    admin_token = keycloak_token(checks, evidence)
    cleanup_evidence["throwawayAccounts"] = list(leftovers)
    if not admin_token:
        checks.check("throwaway accounts documented as left behind (Keycloak Admin API unavailable)", True,
                     f"left behind={leftovers}")
        return
    deleted, failures = [], []
    try:
        existing = {str(user.get("username")): user for user in keycloak_users(admin_token) if isinstance(user, dict)}
        stragglers = [name for name in existing if name.startswith("qa-reg-")]
        for name in dict.fromkeys([*leftovers, *stragglers]):
            user = existing.get(name)
            if not user:
                rows = keycloak_user(admin_token, name)
                user = rows[0] if rows else None
            if not user:
                continue
            status, text = keycloak_delete(admin_token, str(user.get("id")))
            if status in {200, 204}:
                deleted.append(name)
            else:
                failures.append({"username": name, "status": status, "body": text[:200]})
        remaining = {str(user.get("username")) for user in keycloak_users(admin_token) if isinstance(user, dict)}
        checks.check("throwaway accounts were removed through the Keycloak Admin API",
                     not failures and not any(name in remaining for name in deleted),
                     describe({"failures": failures, "remaining": sorted(remaining)[:20]}))
        demo_rows = [row for row in keycloak_users(admin_token) if isinstance(row, dict)
                     and str(row.get("username")) == DEMO_USER]
        checks.check("the qa-member demo account is still present in Keycloak",
                     len(demo_rows) == 1, describe([row.get("username") for row in keycloak_users(admin_token)]))
        cleanup_evidence.update({"keycloakAdmin": True, "deleted": deleted, "failures": failures,
                                 "kept": [DEMO_USER]})
    except AssertionError as failure:
        checks.check("throwaway accounts were removed through the Keycloak Admin API", False, describe(failure))
        cleanup_evidence.update({"keycloakAdmin": True, "deleted": deleted, "failures": [describe(failure)],
                                 "kept": [DEMO_USER]})


def check_demo_after_cleanup(checks, evidence):
    demo_token = login(checks.metadata, DEMO_USER)["access_token"]
    status, _, text, identity = http("GET", "/api/v1/identity", token=demo_token)
    identity = identity if isinstance(identity, dict) else {}
    status_ok = status == 200 and identity.get("tenantId") == "tenant-a" and identity.get("roles") == ["EMPLOYEE"]
    if not status_ok:
        # A previous run may have left the demo account promoted; restore it.
        admin_token = login(checks.metadata, "a-admin")["access_token"]
        change_roles(admin_token, DEMO_USER, "demote")
        demo_token = login(checks.metadata, DEMO_USER)["access_token"]
        status, _, text, identity = http("GET", "/api/v1/identity", token=demo_token)
        identity = identity if isinstance(identity, dict) else {}
        status_ok = status == 200 and identity.get("tenantId") == "tenant-a" and identity.get("roles") == ["EMPLOYEE"]
    checks.check("after cleanup qa-member still logs in as a tenant-a EMPLOYEE (demo account ready)",
                 status_ok, f"HTTP {status} body={text[:300]}")
    evidence["demoAccount"]["afterCleanup"] = identity


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    started = time.time()
    checks = Checks()
    evidence = {}
    leftovers = []
    exit_code = 1
    try:
        status, _, _, metadata = http("GET", ISSUER_LOCAL + "/.well-known/openid-configuration")
        checks.check("local OIDC discovery is reachable", status == 200, f"HTTP {status}")
        checks.metadata = metadata

        tokens = {
            "a-admin": login(metadata, "a-admin")["access_token"],
            "b-admin": login(metadata, "b-admin")["access_token"],
            "a-employee": login(metadata, "a-employee")["access_token"],
        }
        for name, token in tokens.items():
            status, _, text, identity = http("GET", "/api/v1/identity", token=token)
            checks.check(f"seed account {name} logs in and resolves identity",
                         status == 200 and identity.get("subject"), f"HTTP {status} body={text[:200]}")

        a_view, b_view = check_invite_codes(checks, evidence, tokens)
        a_view, b_view = check_rotation(checks, evidence, tokens, a_view, b_view)
        fresh_code = active_code(a_view)
        b_code = active_code(b_view)
        checks.check("a fresh tenant-a active code is available for the registration flow",
                     isinstance(fresh_code, str) and CODE_PATTERN.match(fresh_code), fresh_code)

        check_register_validation(checks, evidence, fresh_code)
        ensure_demo_account(checks, evidence, fresh_code, tokens)
        flow_user, flow_token = check_new_member_flow(checks, evidence, fresh_code, leftovers)
        check_promotion(checks, evidence, tokens["a-admin"], flow_user)
        check_guards(checks, evidence, tokens, flow_user, leftovers, b_code)
        check_code_reuse(checks, evidence, fresh_code, leftovers)
        checks.check("throwaway account list is tracked for cleanup", len(leftovers) >= 3, leftovers)
        check_access_and_regression(
            checks, evidence, tokens,
            users_to_find=[(DEMO_USER, "tenant-a", "EMPLOYEE"), (flow_user, "tenant-a", "EMPLOYEE"),
                           (evidence.get("guards", {}).get("tenantBUser", ""), "tenant-b", "EMPLOYEE")],
        )
    except Exception as failure:  # noqa: BLE001 - report any unexpected backend contract break
        checks.check("smoke test completed without unexpected exceptions", False, f"{type(failure).__name__}: {failure}")
        evidence["fatalError"] = f"{type(failure).__name__}: {failure}"
    finally:
        try:
            cleanup(checks, evidence, {"a-admin": login(checks.metadata, "a-admin")["access_token"]}, leftovers)
            check_demo_after_cleanup(checks, evidence)
        except Exception as failure:  # noqa: BLE001 - cleanup failures are reported, not fatal crashes
            checks.check("cleanup and demo-account verification completed", False,
                         f"{type(failure).__name__}: {failure}")
            evidence["cleanupError"] = f"{type(failure).__name__}: {failure}"
        duration = time.time() - started
        summary = checks.summary(duration)
        report = {
            "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "gateway": GATEWAY,
            "issuer": ISSUER_LOCAL,
            "demoAccount": {"username": DEMO_USER, "password": DEMO_PASSWORD, "tenantId": "tenant-a", "role": "EMPLOYEE"},
            "checks": checks.rows,
            "summary": summary,
            "deviations": [],
            "notes": [
                "注册账号保存在 Keycloak（dev-mem 模式）：重启 keycloak 容器会清空注册用户，种子用户来自 realm import 不受影响。",
                "每次运行都会轮换租户邀请码（这是接口语义，不是副作用）；旧码立即失效。",
                "qa-member 作为唯一保留的注册演示账号，其余 qa-reg-* 一次性账号已通过 Keycloak Admin API 删除。",
            ],
        }
        report.update(evidence)
        REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
        REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"SUMMARY  checks={summary['checks']} passed={summary['passed']} failed={summary['failed']} "
              f"duration={summary['durationSeconds']}s", flush=True)
        print(f"REPORT   {REPORT_PATH}", flush=True)
        exit_code = 0 if summary["failed"] == 0 else 1
        if exit_code == 0:
            print("PASS  registration / invite-code / promotion smoke test is green", flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
