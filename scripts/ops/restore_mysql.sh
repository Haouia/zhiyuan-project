#!/usr/bin/env bash
# 备份恢复演练：把备份还原到同实例的临时影子库，核对行数后清理，不覆盖线上数据。
# 用法：bash scripts/ops/restore_mysql.sh [备份文件]
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python scripts/ops/backup_restore.py restore "$@"
