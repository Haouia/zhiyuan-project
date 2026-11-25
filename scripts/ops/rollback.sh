#!/usr/bin/env bash
# 回滚演练：按发布清单把 Deployment 的镜像切回上一版固定 tag。
# --dry-run 只输出计划并校验清单完整性，不需要 K8s 集群；去掉参数才会执行 kubectl。
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python scripts/ops/rollback.py "$@"
