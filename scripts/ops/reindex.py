"""OpenSearch 影子索引重建演练。

从 MySQL 的分块表重建一个临时索引（含本地测试向量），核对文档数、中文关键词与
k-NN 查询，然后删除临时索引。线上索引和数据库均不改动；生产使用获批向量端点
重新向量化时，替换 local_vector 为对应端点调用即可。
"""
import json
import math
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from smoke import compose, mysql, require  # noqa: E402
from smoke_phase2 import os_request  # noqa: E402

INDEX = "rag-doc-chunks-v1"
LOCAL_MODEL = "local-test-v1"
DIMENSIONS = 128


def db_chunks():
    statement = (
        "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT("
        "'tenantId', c.tenant_id, 'documentId', CAST(c.document_id AS CHAR), "
        "'version', c.version, 'chunkIndex', c.chunk_index, 'title', d.title, "
        "'text', c.chunk_text, 'unit', c.locator_unit, 'start', c.start_position, "
        "'end', c.end_position, 'embeddingModelVersion', c.embedding_model_version)), JSON_ARRAY()) "
        "FROM search_db.doc_chunk c JOIN doc_db.kb_document d "
        "ON d.id = c.document_id AND d.tenant_id = c.tenant_id;"
    )
    return json.loads(mysql(statement).stdout.strip())


def local_vector(text):
    vector = [0.0] * DIMENSIONS
    for char in text:
        lowered = char.lower()
        code = ord(lowered) if len(lowered) == 1 else ord(char)
        vector[code % DIMENSIONS] += 1.0
    length = math.sqrt(sum(value * value for value in vector)) or 1.0
    return [value / length for value in vector]


def os_bulk(path, ndjson):
    result = compose("exec", "-T", "opensearch", "curl", "-sS", "--max-time", "30", "-w", "\n%{http_code}",
                     "-X", "POST", "-H", "Content-Type: application/x-ndjson", "--data-binary", "@-",
                     "http://127.0.0.1:9200" + path, sql=ndjson)
    body, _, status = result.stdout.rpartition("\n")
    require(int(status) == 200, f"bulk returned HTTP {status}: {body[:400]}")
    response = json.loads(body)
    require(not response.get("errors"), "bulk reported item errors: " + body[:400])
    return response


def main():
    rows = db_chunks()
    require(bool(rows), "search_db.doc_chunk 为空，请先上传并发布一个文档再做重建演练")
    models = {row["embeddingModelVersion"] for row in rows}
    require(models <= {LOCAL_MODEL},
            f"检测到非本地向量模型 {models}，演练脚本拒绝擅自向外部端点发送语料")

    shadow = f"{INDEX}-rebuild-{int(time.time())}"
    original = os_request(f"/{INDEX}/_count", {"query": {"match_all": {}}}, expected=(200, 404))
    original_count = original.get("count", 0)
    mapping_response = os_request(f"/{INDEX}/_mapping")
    mapping = mapping_response[INDEX]["mappings"]

    os_request(f"/{shadow}", {
        "settings": {"index.knn": True, "number_of_shards": 1, "number_of_replicas": 0},
        "mappings": mapping,
    }, method="PUT")

    try:
        lines = []
        first_vector = None
        for row in rows:
            vector = local_vector(row["text"])
            first_vector = first_vector or vector
            chunk_id = f"{row['tenantId']}:{row['documentId']}:{row['version']}:{row['chunkIndex']}"
            lines.append(json.dumps({"index": {"_id": chunk_id}}))
            lines.append(json.dumps({
                "tenantId": row["tenantId"], "documentId": row["documentId"], "version": row["version"],
                "chunkIndex": row["chunkIndex"], "title": row["title"], "text": row["text"],
                "unit": row["unit"], "start": row["start"], "end": row["end"],
                "embeddingModelVersion": row["embeddingModelVersion"], "embedding": vector,
            }, ensure_ascii=False))
        os_bulk(f"/{shadow}/_bulk?refresh=wait_for", "\n".join(lines) + "\n")

        rebuilt = os_request(f"/{shadow}/_count", {"query": {"match_all": {}}})["count"]
        require(rebuilt == len(rows) == original_count,
                f"索引重建文档数不一致: db={len(rows)} original={original_count} rebuilt={rebuilt}")
        keyword = os_request(f"/{shadow}/_search", {
            "size": 3, "query": {"multi_match": {"query": "年假", "fields": ["text", "title"]}},
        })
        semantic = os_request(f"/{shadow}/_search", {
            "size": 3, "query": {"knn": {"embedding": {"vector": first_vector, "k": 3}}},
        })
        require(keyword["hits"]["total"]["value"] >= 1 and semantic["hits"]["total"]["value"] >= 1,
                "重建索引的中文关键词或向量查询不可用")
        print(f"REINDEX_OK rows={len(rows)} original={original_count} rebuilt={rebuilt} "
              f"keywordHits={keyword['hits']['total']['value']} knnHits={semantic['hits']['total']['value']} "
              f"shadow={shadow}")
    finally:
        os_request(f"/{shadow}", None, method="DELETE", expected=(200, 404))
        print("REINDEX_CLEANUP 影子索引已删除，线上索引未改动")


if __name__ == "__main__":
    main()
