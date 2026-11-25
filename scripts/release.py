"""生成不可变版本的镜像标签与发布清单，并供回滚演练校验。

用法：
  python scripts/release.py [--revision <sha>] [--force]
  python scripts/release.py --revision <sha> --check

标签形如 0.1.0-<12 位 revision>；同一标签已存在时拒绝覆盖（--force 仅用于重建）。
--check 不修改任何镜像，只校验清单、OCI 标签与上一版清单是否可用于回滚。
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = os.environ.get("RAG_IMAGE_REGISTRY", "ragknowledge")
SERVICES = ("gateway", "doc-service", "search-service", "ai-service",
            "metric-service", "model-primary", "model-backup", "keycloak")
SOURCE_TAG = "dev"
TRACKED = (
    "VERSION", "pom.xml", "compose.yaml", "docker/java.Dockerfile",
    "*/pom.xml", "*/src/main/**/*.java", "*/src/main/resources/*.yml",
    "metric-service/server.py", "metric-service/Dockerfile",
    "docker/model-fixture/*", "docker/keycloak/Dockerfile",
    "eval/golden.json", "scripts/EvalJob.java", "scripts/release.py",
)


def run(*args, env=None, check=True):
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True,
                            encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RuntimeError(f"{' '.join(args)} failed (exit {result.returncode}): {result.stderr.strip()}")
    return result


def source_revision():
    digest = hashlib.sha256()
    files = set()
    for pattern in TRACKED:
        files.update(path for path in ROOT.glob(pattern) if path.is_file())
    for path in sorted(files):
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()[:12]


def manifests():
    files = list((ROOT / "release").glob("*.json"))
    return sorted(files, key=lambda path: json.loads(path.read_text(encoding="utf-8"))["sequence"])


def image_field(image, expression):
    return run("docker", "image", "inspect", image, "--format", expression).stdout.strip()


def ensure_source_images(version, revision):
    missing = [service for service in SERVICES
               if run("docker", "image", "inspect", f"{REGISTRY}/{service}:{SOURCE_TAG}",
                      check=False).returncode != 0]
    if not missing:
        return
    env = dict(os.environ, RAG_IMAGE_TAG=SOURCE_TAG, RAG_VERSION=version, RAG_REVISION=revision)
    result = run("docker", "compose", "build", *missing, env=env, check=False)
    if result.returncode:
        raise RuntimeError(f"docker compose build failed: {result.stderr.strip()[-2000:]}")
    for service in SERVICES:
        run("docker", "image", "inspect", f"{REGISTRY}/{service}:{SOURCE_TAG}")


def build_manifest(version, revision, tag, sequence, previous):
    images = []
    for service in SERVICES:
        source = f"{REGISTRY}/{service}:{SOURCE_TAG}"
        target = f"{REGISTRY}/{service}:{tag}"
        run("docker", "tag", source, target)
        oci_version = image_field(target, '{{index .Config.Labels "org.opencontainers.image.version"}}')
        oci_revision = image_field(target, '{{index .Config.Labels "org.opencontainers.image.revision"}}')
        if oci_version != version or not oci_revision:
            raise RuntimeError(f"{target} has invalid OCI labels: version={oci_version} revision={oci_revision}")
        images.append({
            "service": service, "image": target,
            "id": image_field(target, "{{.Id}}"),
            "ociVersion": oci_version, "ociRevision": oci_revision,
        })
    return {
        "version": version, "revision": revision, "tag": tag,
        "sequence": sequence, "previous": previous,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "images": images,
    }


def check_manifest(version, revision, tag):
    path = ROOT / "release" / f"{tag}.json"
    if not path.exists():
        raise RuntimeError(f"release manifest {path.name} is missing")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest["version"] != version or manifest["tag"] != tag or len(manifest["images"]) != len(SERVICES):
        raise RuntimeError("release manifest does not match the requested version")
    for entry in manifest["images"]:
        if run("docker", "image", "inspect", entry["image"], check=False).returncode != 0:
            raise RuntimeError(f"release image {entry['image']} is missing")
        label = image_field(entry["image"], '{{index .Config.Labels "org.opencontainers.image.version"}}')
        if label != version or entry["id"] != image_field(entry["image"], "{{.Id}}"):
            raise RuntimeError(f"release image {entry['image']} changed after publishing")
    ordered = manifests()
    if len(ordered) < 2 or ordered[-1].name != path.name:
        raise RuntimeError("no previous immutable release is available for rollback")
    print(f"RELEASE_CHECK_OK tag={tag} images={len(manifest['images'])} previous={manifest['previous']}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    revision = (args.revision or os.environ.get("GITHUB_SHA")
                or os.environ.get("RAG_REVISION") or source_revision())[:12]
    tag = f"{version}-{revision}"
    (ROOT / "release").mkdir(exist_ok=True)
    if args.check:
        check_manifest(version, revision, tag)
        return
    path = ROOT / "release" / f"{tag}.json"
    if path.exists() and not args.force:
        raise SystemExit(f"release {tag} already exists; immutable tags must not be overwritten (use --force)")
    ordered = manifests()
    previous = json.loads(ordered[-1].read_text(encoding="utf-8"))["tag"] if ordered else None
    sequence = (json.loads(ordered[-1].read_text(encoding="utf-8"))["sequence"] + 1) if ordered else 1
    ensure_source_images(version, revision)
    manifest = build_manifest(version, revision, tag, sequence, previous)
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"RELEASE_OK tag={tag} sequence={sequence} previous={previous} images={len(manifest['images'])}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, SystemExit) as failure:
        print(f"RELEASE_FAILED {failure}", file=sys.stderr)
        raise SystemExit(1)
