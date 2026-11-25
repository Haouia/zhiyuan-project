"""启动后自检：登录 + 身份 + 文档数 + 一次真实问答（走检索 + DeepSeek）。"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.request import ProxyHandler, Request, build_opener, urlopen

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import smoke  # noqa: E402

metadata = json.load(urlopen(smoke.ISSUER + "/.well-known/openid-configuration", timeout=10))  # noqa: S310
token = smoke.login(metadata, "a-admin")["access_token"]
opener = build_opener(ProxyHandler({}))

identity = smoke.api("/api/v1/identity", 200, token)
documents = smoke.api("/api/v1/documents?page=0&size=100", 200, token)

request = Request(
    smoke.GATEWAY + "/api/v1/questions",
    json.dumps({"question": "出差住宿标准是多少？"}, ensure_ascii=False).encode("utf-8"),
    {"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    method="POST",
)
with opener.open(request, timeout=90) as response:
    answer = json.loads(response.read())

print(json.dumps({
    "identity": {"tenantId": identity.get("tenantId"), "roles": identity.get("roles")},
    "documents": {"count": len(documents.get("items", [])), "titles": [i.get("title") for i in documents.get("items", [])]},
    "question": {"status": answer.get("status"), "citations": len(answer.get("citations") or []), "answer": answer.get("answer", "")[:120]},
}, ensure_ascii=False, indent=2))
