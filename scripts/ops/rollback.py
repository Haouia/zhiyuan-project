"""回滚演练：按发布清单把 Deployment 镜像切回上一版固定 tag。

  python scripts/ops/rollback.py --dry-run   # 只输出计划并校验清单，不需要 K8s 集群
  python scripts/ops/rollback.py            # 执行 kubectl 回滚
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    files = sorted((ROOT / "release").glob("*.json"),
                   key=lambda path: json.loads(path.read_text(encoding="utf-8"))["sequence"])
    if len(files) < 2:
        raise SystemExit("ROLLBACK_FAILED 至少需要两份发布清单才能演练回滚")
    current = json.loads(files[-1].read_text(encoding="utf-8"))
    previous = json.loads(files[-2].read_text(encoding="utf-8"))
    if current["tag"] == previous["tag"] or current["previous"] != previous["tag"]:
        raise SystemExit("ROLLBACK_FAILED 上一版清单与当前的 previous 字段不一致")

    now = {item["service"]: item["image"] for item in current["images"]}
    before = {item["service"]: item["image"] for item in previous["images"]}
    changed = sorted(service for service in now if now[service] != before.get(service))
    if not changed:
        raise SystemExit("ROLLBACK_FAILED 上一版与当前版使用完全相同的镜像 tag")
    commands = [f"kubectl -n rag-knowledge set image deployment/{service} {service}={before[service]}"
                for service in changed]
    commands += [f"kubectl -n rag-knowledge rollout status deployment/{service} --timeout=180s"
                 for service in changed]
    print(f"ROLLBACK_PLAN current={current['tag']} previous={previous['tag']} services={len(changed)}")
    for command in commands:
        print("  " + command)
    if args.dry_run:
        print("DRY_RUN_OK 回滚计划完整，未对集群执行变更")
    else:
        for command in commands:
            subprocess.run(command.split(), check=True)
        print("ROLLBACK_OK 已回滚到上一版镜像 tag")


if __name__ == "__main__":
    main()
