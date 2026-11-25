"""为 CI 或全新环境生成与 scripts/start.ps1 等价的本地凭证文件。

仅在 .env 不存在时写入；已有数据卷时必须恢复原 .env，避免数据库凭证失配。
"""
import base64
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KEYS = (
    "RAG_DB_ROOT_PASSWORD", "RAG_DOC_DB_PASSWORD", "RAG_SEARCH_DB_PASSWORD",
    "RAG_GATEWAY_DOC_TOKEN", "RAG_SEARCH_DOC_TOKEN", "RAG_GATEWAY_AI_TOKEN",
    "RAG_AI_SEARCH_TOKEN", "RAG_AI_DOC_TOKEN", "RAG_METRIC_TOKEN",
    "RAG_NACOS_SERVER_ID", "RAG_NACOS_SIGNING_KEY",
)


def main():
    target = ROOT / ".env"
    if target.exists():
        print("ci-env: .env already exists, keeping existing credentials")
        return
    lines = []
    for key in KEYS:
        value = (base64.b64encode(secrets.token_bytes(32)).decode()
                 if key == "RAG_NACOS_SIGNING_KEY" else secrets.token_hex(32))
        lines.append(f"{key}={value}")
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"ci-env: generated {len(KEYS)} credentials")


if __name__ == "__main__":
    main()
