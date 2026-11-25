"""第五阶段验收：工程化交付与可运维性。

覆盖：接口契约、Prometheus 指标与健康探针、traceId 跨服务串联、评测脱敏与金标门槛、
K8s 配置校验、固定版本镜像与不可变标签、发布清单回滚演练、MySQL 备份恢复、
OpenSearch 影子索引重建。所有操作均在独立本地验收环境中进行，测试数据结束后清理。
"""
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from smoke import ROOT, compose, require  # noqa: E402
from smoke_phase2 import MARKER, cleanup, upload, wait_status  # noqa: E402
from smoke_phase3 import accounts, ask, citation_for  # noqa: E402
from smoke_phase4 import BACKUP, PRIMARY, control, eval_job, latest_report, redis_flush  # noqa: E402

DOCUMENTS = "/api/v1/documents"
JAVA_SERVICES = {"gateway": 8080, "doc-service": 8082, "search-service": 8083, "ai-service": 8081}


def python_script(name, *args, expect=0):
    result = subprocess.run([sys.executable, name, *args], cwd=ROOT, capture_output=True,
                            encoding="utf-8", errors="replace", timeout=300)
    output = result.stdout + result.stderr
    require(result.returncode == expect, f"{name} {' '.join(args)} 返回 {result.returncode}: {output[-1200:]}")
    return output


def compose_output(*args):
    result = compose(*args, check=False)
    return result.stdout + result.stderr


def check_contract():
    output = python_script("scripts/contract_tests.py")
    require("CONTRACT_OK" in output, "契约测试未输出 CONTRACT_OK")
    print("PASS: 8 条外部 + 7 条内部接口契约，运行时状态码、响应结构与错误信封", flush=True)


def check_health_probes():
    for service, port in JAVA_SERVICES.items():
        for probe in ("liveness", "readiness"):
            status = compose("exec", "-T", service, "curl", "-fsS", "-o", "/dev/null", "-w", "%{http_code}",
                             f"http://127.0.0.1:{port}/actuator/health/{probe}").stdout.strip()
            require(status == "200", f"{service} /actuator/health/{probe} 返回 {status}")
    print("PASS: 四个 Java 服务的 liveness / readiness 分组探针可用（K8s 就绪探针）", flush=True)


def service_metrics(service, port):
    return compose("exec", "-T", service, "curl", "-fsS",
                   f"http://127.0.0.1:{port}/actuator/prometheus").stdout


def check_metrics(trace_id):
    for service, port in JAVA_SERVICES.items():
        text = service_metrics(service, port)
        require("# HELP" in text or "_total" in text or "jvm_" in text,
                f"{service} 的 Prometheus 端点不可用")
    ai = service_metrics("ai-service", 8081)
    for metric in ("rag_questions_seconds_bucket", "rag_question_results_total", "rag_model_tokens_total"):
        require(metric in ai, f"ai-service 缺少 {metric} 指标")
    for service, port in (("doc-service", 8082), ("search-service", 8083)):
        require("rag_kafka_pending_events" in service_metrics(service, port),
                f"{service} 缺少 Kafka 积压指标")
    print("PASS: Prometheus 端点与问答延迟/结果、Token 用量、Kafka 积压指标可被采集", flush=True)


def check_trace(trace_id):
    require(bool(trace_id) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", trace_id),
            f"问答响应缺少有效 traceId: {trace_id}")
    for service in ("gateway", "ai-service", "search-service", "doc-service"):
        logs = compose_output("logs", "--since", "15m", service)
        require(trace_id in logs, f"{service} 日志中找不到 traceId={trace_id}")
    ai_logs = compose_output("logs", "--since", "15m", "ai-service")
    require(f"model traceId={trace_id}" in ai_logs, "模型调用环节未按 traceId 串联")
    print(f"PASS: traceId={trace_id} 串联网关、问答、检索、原文与模型调用日志", flush=True)


def check_golden_and_redaction():
    job = eval_job()
    require("Evaluation OK" in job.stdout, f"离线评测未成功: {job.stdout[-400:]} {job.stderr[-400:]}")
    report = latest_report()
    require(report["status"] == "OK" and report.get("redacted") is True, "报告未标记脱敏")
    golden = json.loads((ROOT / "eval" / "golden.json").read_text(encoding="utf-8"))
    require(len(golden["samples"]) == 40 and len(report["samples"]) == 40,
            f"金标或报告样本数不是 40: {len(golden['samples'])}/{len(report['samples'])}")

    serialized = json.dumps(report["samples"], ensure_ascii=False)
    for secret in ("13800138000", "zhangsan@example.com"):
        require(secret not in serialized, f"归档报告仍含敏感原文 {secret}")
    require("[PHONE]" in serialized and "[EMAIL]" in serialized, "归档报告未出现脱敏标记")

    answerable = [index for index, sample in enumerate(golden["samples"]) if "reference" in sample]
    insufficient = [index for index, sample in enumerate(golden["samples"]) if "reference" not in sample]
    require(len(answerable) == 30 and len(insufficient) == 10,
            f"金标构成不符合 30/10: {len(answerable)}/{len(insufficient)}")
    faithfulness = [report["samples"][index]["faithfulness"] for index in answerable]
    recall = [report["samples"][index]["context_recall"] for index in answerable]
    require(all(isinstance(value, (int, float)) for value in faithfulness + recall), "可答题出现缺分")
    require(sum(faithfulness) / len(faithfulness) >= 0.90,
            f"平均 Faithfulness 低于 0.90: {sum(faithfulness) / len(faithfulness):.3f}")
    require(sum(recall) / len(recall) >= 0.85,
            f"平均 Context Recall 低于 0.85: {sum(recall) / len(recall):.3f}")
    require(all(report["samples"][index]["context_recall"] is None for index in insufficient),
            "资料不足题不应计算 Context Recall")
    require(abs(report["samples"][2]["faithfulness"]) < 1e-6, "负例未被记录为低分（应列入失败清单）")
    print(f"PASS: 40 题金标（30/10）、均值 Faithfulness={sum(faithfulness)/len(faithfulness):.3f} "
          f"Context Recall={sum(recall)/len(recall):.3f}，报告归档前已脱敏", flush=True)


def check_k8s_and_release():
    output = python_script("scripts/validate_k8s.py")
    require("K8S_VALIDATE_OK" in output, "K8s 配置初始校验未通过")

    # 演练用的两份清单可在重跑时重建；真实发布清单一经生成不可删除或覆盖。
    version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    for revision in ("phase5prev", "phase5curr"):
        manifest = ROOT / "release" / f"{version}-{revision}.json"
        if manifest.exists():
            manifest.unlink()

    python_script("scripts/release.py", "--revision", "phase5prev")
    python_script("scripts/release.py", "--revision", "phase5curr")
    duplicate = python_script("scripts/release.py", "--revision", "phase5curr", expect=1)
    require("immutable" in duplicate, "同一版本标签可被覆盖，违反不可变镜像要求")
    checked = python_script("scripts/release.py", "--revision", "phase5curr", "--check")
    require("RELEASE_CHECK_OK" in checked, "发布清单校验未通过")

    aligned = python_script("scripts/validate_k8s.py", "--align-release")
    require("K8S_ALIGNED" in aligned and "K8S_VALIDATE_OK" in aligned, "K8s 镜像未对齐到发布清单")
    kustomization = (ROOT / "k8s/kustomization.yaml").read_text(encoding="utf-8")
    require("0.1.0-phase5curr" in kustomization, "K8s 未使用发布清单中的固定版本 tag")

    rollback = python_script("scripts/ops/rollback.py", "--dry-run")
    require("DRY_RUN_OK" in rollback, "回滚演练未生成完整计划")
    print("PASS: 固定版本镜像、不可变标签、发布清单、K8s 对齐与上一版镜像回滚演练", flush=True)


def check_backup_and_reindex():
    backup = python_script("scripts/ops/backup_restore.py", "backup")
    require("BACKUP_OK" in backup, "MySQL 备份失败")
    match = re.search(r"file=(\S+)", backup)
    require(match is not None, "备份脚本未输出文件路径")
    restore = python_script("scripts/ops/backup_restore.py", "restore", match.group(1))
    require("RESTORE_OK" in restore and "RESTORE_CLEANUP" in restore, "MySQL 影子恢复未通过或未清理")
    reindex = python_script("scripts/ops/reindex.py")
    require("REINDEX_OK" in reindex and "REINDEX_CLEANUP" in reindex, "OpenSearch 影子索引重建未通过或未清理")
    print("PASS: MySQL 逻辑备份 + 影子库恢复校验、OpenSearch 从 DB 影子索引重建与查询", flush=True)


def check():
    control(PRIMARY, "ok", reset=True)
    control(BACKUP, "ok", reset=True)
    redis_flush()

    check_contract()
    check_health_probes()

    a_admin, a_staff = accounts(("a-admin", "a-employee"))
    document = upload(a_admin, DOCUMENTS, MARKER + "第五阶段年假政策", "policy.txt",
                      "入职满一年，可休 7 天公司年假。".encode(), uuid.uuid4().hex)["documentId"]
    wait_status(a_admin, f"{DOCUMENTS}/{document}/versions/1", "READY", 1)
    reply = ask(a_staff)
    citation_for(reply, document, 1, 7)
    trace_id = reply["traceId"]

    check_metrics(trace_id)
    check_trace(trace_id)
    check_golden_and_redaction()
    check_backup_and_reindex()
    check_k8s_and_release()


if __name__ == "__main__":
    try:
        check()
        print("PHASE5_OK 第五阶段验收全部通过", flush=True)
    finally:
        control(PRIMARY, "ok", reset=True)
        control(BACKUP, "ok", reset=True)
        cleanup()
        redis_flush()
