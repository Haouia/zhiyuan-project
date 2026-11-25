"""K8s 配置校验：不依赖第三方 YAML 库，必要时用 kubectl --dry-run=client 补充校验。

  python scripts/validate_k8s.py                  # 只读校验
  python scripts/validate_k8s.py --align-release # 把镜像 tag 对齐到最新发布清单，再校验
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
K8S = ROOT / "k8s"
TAG_RE = re.compile(r"0\.1\.0-[A-Za-z0-9-]+")
APP_DEPLOYMENTS = ("gateway", "doc-service", "search-service", "ai-service", "metric-service")


def documents(text):
    return [chunk for chunk in re.split(r"(?m)^---[ \t]*$", text) if chunk.strip()]


def deployment_docs(path):
    for doc in documents(path.read_text(encoding="utf-8")):
        if re.search(r"(?m)^kind:\s*Deployment\s*$", doc):
            yield doc


def require(condition, message, errors):
    if not condition:
        errors.append(message)


def align_release():
    files = sorted((ROOT / "release").glob("*.json"),
                   key=lambda path: json.loads(path.read_text(encoding="utf-8"))["sequence"])
    if not files:
        raise SystemExit("K8S_ALIGN_FAILED 尚无发布清单，请先运行 scripts/release.py")
    tag = json.loads(files[-1].read_text(encoding="utf-8"))["tag"]
    for name in ("20-deployments.yaml", "kustomization.yaml"):
        path = K8S / name
        text = path.read_text(encoding="utf-8")
        updated = TAG_RE.sub(tag, text)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
    print(f"K8S_ALIGNED tag={tag}")


def validate():
    errors = []
    app_tags = []
    for doc in deployment_docs(K8S / "20-deployments.yaml"):
        match = re.search(r"(?m)^  name:\s*(\S+)\s*$", doc)
        name = match.group(1) if match else "?"
        images = re.findall(r"(?m)^\s+image:\s*(\S+)\s*$", doc)
        require(bool(images), f"{name}: 缺少 image", errors)
        for image in images:
            require(":latest" not in image and not image.endswith(":dev"),
                    f"{name}: 镜像未使用不可变 tag: {image}", errors)
            if name in APP_DEPLOYMENTS:
                tag = re.search(r":(0\.1\.0-[A-Za-z0-9-]+)$", image)
                require(tag is not None, f"{name}: 应用镜像缺少语义版本 tag: {image}", errors)
                if tag:
                    app_tags.append(tag.group(1))
        for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
            require(re.search(rf"(?m)^\s+{probe}:", doc) is not None, f"{name}: 缺少 {probe}", errors)
        require("requests:" in doc and "limits:" in doc, f"{name}: 缺少资源 requests/limits", errors)
        require("type: RollingUpdate" in doc, f"{name}: 未配置滚动发布", errors)
        require("revisionHistoryLimit:" in doc, f"{name}: 未保留发布历史用于回滚", errors)
        require("secretRef:" in doc, f"{name}: 密钥未通过 Secret 引用", errors)
    require(len(app_tags) == len(APP_DEPLOYMENTS),
            f"应用 Deployment 数量异常: 期望 {len(APP_DEPLOYMENTS)}, 实际 {len(app_tags)}", errors)
    require(len(set(app_tags)) == 1, f"应用镜像 tag 不一致: {set(app_tags)}", errors)

    for path in K8S.glob("*.yaml"):
        for doc in deployment_docs(path):
            for image in re.findall(r"(?m)^\s+image:\s*(\S+)\s*$", doc):
                require(":latest" not in image, f"{path.name}: 禁止 latest 镜像 {image}", errors)
        if path.name != "secret.example.yaml":
            text = path.read_text(encoding="utf-8")
            require(not re.search(r"(?m)^\s+RAG_[A-Z_]*(?:PASSWORD|TOKEN|KEY):\s*(?!<)[^\s]", text),
                    f"{path.name}: 疑似内联明文凭证", errors)

    secret = (K8S / "secret.example.yaml").read_text(encoding="utf-8")
    values = re.findall(r"(?m)^\s+([A-Z_]+):\s*(\S.*)$", secret)
    require(len(values) >= 10, "secret 模板缺少字段", errors)
    for key, value in values:
        raw = value.strip().strip('"').strip("'")
        require(re.fullmatch(r"<[^>]+>", raw) is not None, f"secret 模板 {key} 存在非占位值", errors)

    kustom = (K8S / "kustomization.yaml").read_text(encoding="utf-8")
    resources = re.findall(r"(?m)^\s+-\s+([\w.-]+\.yaml)\s*$", kustom)
    require(bool(resources), "kustomization 未声明 resources", errors)
    for resource in resources:
        require((K8S / resource).exists(), f"kustomization 引用不存在的文件 {resource}", errors)
    require("secret.example.yaml" not in resources, "kustomization 不应部署密钥模板", errors)
    new_tags = re.findall(r"(?m)^\s+newTag:\s*(\S+)\s*$", kustom)
    require(len(new_tags) == len(APP_DEPLOYMENTS), f"kustomization 镜像映射数量异常: {len(new_tags)}", errors)
    require(set(new_tags) == set(app_tags),
            f"kustomization newTag 与 Deployment 不一致: {set(new_tags)} vs {set(app_tags)}", errors)
    release = re.search(r"(?m)^\s+rag\.knowledge/release:\s*(\S+)\s*$", kustom)
    require(release is not None and release.group(1) in app_tags,
            "kustomization release 注解与镜像 tag 不一致", errors)

    dashboard_path = ROOT / "observability/grafana/dashboards/rag-overview.json"
    for relative in ("observability/prometheus/prometheus.yml",
                     "observability/grafana/provisioning/datasources/prometheus.yaml",
                     "observability/grafana/provisioning/dashboards/rag.yaml",
                     "observability/grafana/dashboards/rag-overview.json",
                     "observability/skywalking/agent-patch.yaml"):
        require((ROOT / relative).exists(), f"缺少可观测性配置 {relative}", errors)
    dashboard = json.loads(dashboard_path.read_text(encoding="utf-8"))
    require(len(dashboard["panels"]) >= 6, "Grafana 面板不足 6 项", errors)
    expressions = " ".join(target["expr"] for panel in dashboard["panels"] for target in panel["targets"])
    for metric in ("rag_questions_seconds_bucket", "rag_question_results_total", "rag_kafka_pending_events",
                   "rag_index_failures_total", "rag_model_failover_total", "rag_model_tokens_total"):
        require(metric in expressions, f"Grafana 缺少 {metric} 指标", errors)

    kubectl = shutil.which("kubectl")
    if kubectl:
        result = subprocess.run([kubectl, "apply", "--dry-run=client", "--validate=false", "-k", "k8s"], cwd=ROOT,
                                capture_output=True, encoding="utf-8", errors="replace", timeout=90)
        if result.returncode == 0:
            print("kubectl client dry-run: OK")
        else:
            print("kubectl client dry-run: WARN " + (result.stderr.strip().splitlines() or [""])[-1][:200])
    else:
        print("kubectl client dry-run: SKIPPED (kubectl 未安装)")

    if errors:
        for error in errors:
            print("K8S_ERROR " + error, file=sys.stderr)
        raise SystemExit(1)
    print(f"K8S_VALIDATE_OK deployments={len(app_tags)} tag={app_tags[0]} "
          "探针/资源限制/滚动发布/密钥引用/Grafana 指标 全部通过")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--align-release", action="store_true")
    args = parser.parse_args()
    if args.align_release:
        align_release()
    validate()
