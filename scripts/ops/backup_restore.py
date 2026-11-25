"""MySQL 逻辑备份与影子恢复演练（跨平台，CI 与本地验收共用）。

  python scripts/ops/backup_restore.py backup
  python scripts/ops/backup_restore.py restore [备份文件]

恢复把备份导入同实例的临时影子库，核对行数后删除影子库，线上数据不被覆盖。
"""
import hashlib
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKUPS = ROOT / "backups"
MYSQL = ["docker", "compose", "exec", "-T", "mysql", "bash", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 '
         '--batch --skip-column-names']


def run(args, stdin=None, text=True, timeout=180):
    return subprocess.run(args, cwd=ROOT, input=stdin, capture_output=True,
                          encoding="utf-8" if text else None,
                          errors="replace" if text else None, timeout=timeout)


def mysql(statement):
    result = run(MYSQL, stdin=statement)
    if result.returncode:
        raise SystemExit("RESTORE_FAILED mysql 执行失败: " + (result.stderr or "")[-800:])
    return (result.stdout or "").strip()


def backup():
    BACKUPS.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = BACKUPS / f"mysql-{stamp}.sql"
    result = run(["docker", "compose", "exec", "-T", "mysql", "bash", "-c",
                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction '
                  '--default-character-set=utf8mb4 --hex-blob --databases doc_db search_db'], text=False)
    if result.returncode or b"CREATE TABLE" not in result.stdout:
        raise SystemExit("BACKUP_FAILED mysqldump 输出异常: " + (result.stderr or b"")[-800:].decode("utf-8", "replace"))
    out.write_bytes(result.stdout)
    digest = hashlib.sha256(result.stdout).hexdigest()
    (BACKUPS / f"{out.name}.sha256").write_text(f"{digest}  {out.name}\n", encoding="utf-8")
    print(f"BACKUP_OK file=backups/{out.name} bytes={len(result.stdout)}")


def restore(file_name=None):
    target = Path(file_name) if file_name else None
    if target is None:
        candidates = sorted(BACKUPS.glob("mysql-*.sql"), key=lambda path: path.stat().st_mtime, reverse=True)
        if not candidates:
            raise SystemExit("RESTORE_FAILED 未找到备份文件")
        target = candidates[0]
    if not target.is_absolute():
        target = ROOT / target
    if not target.is_file():
        raise SystemExit(f"RESTORE_FAILED 备份不存在: {target}")
    checksum = Path(str(target) + ".sha256")
    if not checksum.is_file() or checksum.read_text(encoding="utf-8").split()[0] != hashlib.sha256(target.read_bytes()).hexdigest():
        raise SystemExit("RESTORE_FAILED 备份校验和缺失或不匹配")
    raw = target.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("latin-1")
    shadow_sql = (text.replace("`doc_db`", "`rag_restore_doc`")
                   .replace("`search_db`", "`rag_restore_search`"))
    print(f"RESTORE_CHECK verifying {target.relative_to(ROOT)}")
    mysql("DROP DATABASE IF EXISTS rag_restore_doc; DROP DATABASE IF EXISTS rag_restore_search;")
    try:
        mysql(shadow_sql)
        counts = mysql("SELECT (SELECT COUNT(*) FROM doc_db.kb_document),"
                       "(SELECT COUNT(*) FROM rag_restore_doc.kb_document),"
                       "(SELECT COUNT(*) FROM search_db.doc_chunk),"
                       "(SELECT COUNT(*) FROM rag_restore_search.doc_chunk);")
        original_doc, restored_doc, original_chunk, restored_chunk = [int(value) for value in counts.split("\t")]
        if original_doc != restored_doc or original_chunk != restored_chunk:
            raise SystemExit(f"RESTORE_FAILED 行数不一致 {original_doc}/{restored_doc} {original_chunk}/{restored_chunk}")
        print(f"RESTORE_OK doc_db.kb_document={original_doc} search_db.doc_chunk={original_chunk} 影子库行数一致")
    finally:
        mysql("DROP DATABASE IF EXISTS rag_restore_doc; DROP DATABASE IF EXISTS rag_restore_search;")
        print("RESTORE_CLEANUP 影子库已删除，线上数据未改动")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "backup"
    if command == "backup":
        backup()
    elif command == "restore":
        restore(sys.argv[2] if len(sys.argv) > 2 else None)
    else:
        raise SystemExit("用法: backup_restore.py backup | restore [文件]")
