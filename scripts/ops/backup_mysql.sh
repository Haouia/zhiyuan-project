#!/usr/bin/env bash
# MySQL 逻辑备份：doc_db + search_db，附 SHA-256 校验和。
# 逻辑实现在 backup_restore.py，供 Windows 本地与 CI 共用（避免依赖 WSL/bash 差异）。
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python scripts/ops/backup_restore.py backup "$@"
