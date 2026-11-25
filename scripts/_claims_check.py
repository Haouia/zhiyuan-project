"""调试：打印 a-admin 访问令牌的 roles/tenant 声明，并重试 /identity。"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener, urlopen

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import smoke  # noqa: E402

metadata = json.load(urlopen(smoke.ISSUER + "/.well-known/openid-configuration", timeout=10))  # noqa: S310
token = smoke.login(metadata, "a-admin")["access_token"]
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
print("claims:", json.dumps({k: claims.get(k) for k in ("preferred_username", "tenant_id", "realm_access", "resource_access", "azp", "iss")}, ensure_ascii=False))

opener = build_opener(ProxyHandler({}))
for path in ("/api/v1/identity", "/api/v1/documents?page=0&size=5", "/api/v1/invite-codes"):
    request = Request(smoke.GATEWAY + path, None, {"Authorization": f"Bearer {token}"})
    try:
        with opener.open(request, timeout=30) as response:
            body = response.read().decode("utf-8", "replace")
            print(f"{path} -> {response.status} {body[:200]}")
    except HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        print(f"{path} -> {error.code} {body[:300]}")
