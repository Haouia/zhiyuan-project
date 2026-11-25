import concurrent.futures
import io
import json
import time
import uuid
import zipfile

from smoke import GATEWAY, ISSUER, NoRedirect, ProxyHandler, Request, api, build_opener, compose, login, mysql, request, require
from urllib.error import HTTPError


INDEX = "/rag-doc-chunks-v1"
PARSED = "rag.document.parsed.v1"
RESULT = "rag.document.result.v1"
MARKER = "Phase2-" + uuid.uuid4().hex + "-"


def upload(token, path, title, filename, data, key, expected=202):
    boundary = uuid.uuid4().hex
    body = b""
    if title is not None:
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="title"\r\n\r\n{title}\r\n').encode()
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
             f'Content-Type: application/octet-stream\r\n\r\n').encode()
    body += data + f"\r\n--{boundary}--\r\n".encode()
    headers = {"Authorization": "Bearer " + token, "Content-Type": f"multipart/form-data; boundary={boundary}"}
    if key is not None:
        headers["Idempotency-Key"] = key
    opener = build_opener(NoRedirect(), ProxyHandler({}))
    try:
        response = opener.open(Request(GATEWAY + path, body, headers, method="POST"), timeout=25)
    except HTTPError as error:
        response = error
    with response:
        require(response.status == expected, f"Upload {path}: expected HTTP {expected}, received {response.status}")
        result = json.load(response)
    if expected >= 400:
        require(all(result.get(field) for field in ("code", "message", "traceId")), f"Incomplete upload error for {expected}")
    return result


def wait_status(token, path, expected, active, code=None):
    end = time.monotonic() + 80
    observed = set()
    while time.monotonic() < end:
        result = api(path, 200, token)
        observed.add(result["status"])
        if result["status"] == expected:
            require(result["activeVersion"] == active and result["errorCode"] == code, f"Unexpected state at {path}: {result}")
            return result
        if result["status"] == "FAILED":
            raise AssertionError(f"Unexpected failure at {path}: {result['errorCode']}")
        time.sleep(.35)
    raise AssertionError(f"{path} did not reach {expected}. Observed {sorted(observed)}")


def os_request(path, payload=None, method=None, expected=200):
    args = ["exec", "-T", "opensearch", "curl", "-sS", "--max-time", "12", "-w", "\n%{http_code}"]
    if method:
        args.extend(["-X", method])
    if payload is not None:
        args.extend(["-H", "Content-Type: application/json", "--data-binary", "@-"])
    result = compose(*args, "http://127.0.0.1:9200" + path,
                     sql=None if payload is None else json.dumps(payload))
    body, _, status = result.stdout.rpartition("\n")
    require(int(status) in ([expected] if isinstance(expected, int) else expected),
            f"OpenSearch {path} returned HTTP {status}: {body[:500]}")
    return json.loads(body)


def filters(tenant, document, version=None):
    terms = {"tenantId": tenant, "documentId": document}
    if version is not None:
        terms["version"] = version
    return [{"term": {key: value}} for key, value in terms.items()]


def chunks(tenant, document, version):
    payload = {"query": {"bool": {"filter": filters(tenant, document, version)}}, "size": 20,
               "sort": [{"chunkIndex": "asc"}]}
    return os_request(INDEX + "/_search", payload)["hits"]["hits"]


def parsed(tenant, document, version, expected=200):
    path = f"http://doc-service:8082/internal/v1/documents/{document}/versions/{version}/parsed"
    command = ('exec curl -sS --max-time 10 -w "\\n%{http_code}" -H "Authorization: Bearer $RAG_SEARCH_DOC_TOKEN" '
               '-H "X-Tenant-Id: $1" "$2"')
    result = compose("exec", "-T", "search-service", "sh", "-c", command, "parsed-probe", tenant, path)
    body, _, status = result.stdout.rpartition("\n")
    require(int(status) == expected, f"Parsed version returned HTTP {status}")
    return json.loads(body)


def positions(version):
    return [(item["number"], version["text"][item["start"]:item["end"]])
            for item in version["locations"]["positions"]]


def publish(topic, event, count=1):
    key = f"{event['tenantId']}:{event['documentId']}"
    line = key + "|" + json.dumps(event, separators=(",", ":")) + "\n"
    compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
            "--topic", topic, "--property", "parse.key=true", "--property", "key.separator=|", sql=line * count)


def outbox(schema, document, version):
    table = f"{schema}.event_outbox"
    return json.loads(mysql(f"SELECT payload FROM {table} WHERE document_id={int(document)} AND version={version};").stdout)


def wait_consumed(group, topic):
    for _ in range(6):
        result = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server",
                         "kafka:9092", "--group", group, "--describe")
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) >= 6 and fields[:2] == [group, topic] and fields[5] == "0":
                return
        time.sleep(.5)
    raise AssertionError(f"{group} still has lag on {topic}")


def dlq_end():
    result = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "kafka:9092",
                     "--topic", PARSED + ".DLT")
    return int(result.stdout.strip().rsplit(":", 1)[1])


def docx_fixture():
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", """
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".strip())
        archive.writestr("_rels/.rels", """
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".strip())
        archive.writestr("word/document.xml", """
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p/>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格里的段落</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:r><w:t>末尾段落</w:t></w:r></w:p></w:body></w:document>""".strip())
    return output.getvalue()


def pdf_fixture(scanned=False):
    contents = ([b"q 100 0 0 100 20 20 cm BI /W 1 /H 1 /BPC 8 /CS /G ID \x7f\nEI Q"] if scanned else [
        b"BT /F1 14 Tf 40 740 Td (Vacation Policy) Tj ET",
        b"BT /F1 14 Tf 40 740 Td (Page Two) Tj ET",
    ])
    kids = " ".join(f"{4 + i * 2} 0 R" for i in range(len(contents)))
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", f"<< /Type /Pages /Count {len(contents)} /Kids [{kids}] >>".encode(),
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    for i, data in enumerate(contents):
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> "
                       f"/Contents {5 + i * 2} 0 R >>".encode())
        objects.append(f"<< /Length {len(data)} >>\nstream\n".encode() + data + b"\nendstream")
    document = b"%PDF-1.4\n"
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(document))
        document += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    cross_reference = len(document)
    document += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    document += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets)
    return document + f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{cross_reference}\n%%EOF\n".encode()


def cleanup():
    found = mysql(f"SELECT id, tenant_id FROM doc_db.kb_document WHERE title LIKE '{MARKER}%' "
                  "AND tenant_id IN ('tenant-a','tenant-b');").stdout.splitlines()
    for line in found:
        document, tenant = line.split("\t")
        doc_id = int(document)
        selector = f"tenant_id='{tenant}' AND document_id={doc_id}"
        os_request(INDEX + "/_delete_by_query?refresh=true&conflicts=proceed",
                   {"query": {"bool": {"filter": filters(tenant, document)}}}, expected=(200, 404))
        mysql(f"START TRANSACTION; DELETE FROM search_db.doc_chunk WHERE {selector}; "
              f"DELETE FROM search_db.event_outbox WHERE {selector}; DELETE FROM doc_db.event_outbox WHERE {selector}; "
              f"DELETE FROM doc_db.upload_request WHERE {selector}; DELETE FROM doc_db.kb_document_version WHERE document_id={doc_id}; "
              f"DELETE FROM doc_db.kb_document WHERE id={doc_id} AND tenant_id='{tenant}'; COMMIT;")


def check():
    _, _, raw = request(build_opener(NoRedirect(), ProxyHandler({})), ISSUER + "/.well-known/openid-configuration")
    metadata = json.loads(raw)
    a, b, employee = [login(metadata, name)["access_token"] for name in ("a-admin", "b-admin", "a-employee")]
    endpoint = "/api/v1/documents"
    title = MARKER + "年假政策"
    key = uuid.uuid4().hex
    seven = "入职满一年，可休 7 天公司年假。\r\n\r\n以现行版本为准。".encode()
    nine = "入职满一年，可休 9 天公司年假。".encode()
    upload(employee, endpoint, title, "policy.txt", seven, key, 403)
    upload(a, endpoint, title, "policy.txt", seven, None, 400)
    upload(a, endpoint, title, "policy.exe", b"file", uuid.uuid4().hex, 415)
    upload(a, endpoint, title, "bad.txt", b"\xff\x80", uuid.uuid4().hex, 415)
    upload(a, endpoint, title, "large.txt", b"a" * (10 * 1024 * 1024 + 1), uuid.uuid4().hex, 413)

    v1 = upload(a, endpoint, title, "policy.txt", seven, key)
    require(v1["status"] == "PENDING" and v1["version"] == 1, "First upload did not return a durable pending version")
    document = v1["documentId"]
    base = f"{endpoint}/{document}/versions"
    require(upload(a, endpoint, title, "policy.txt", seven, key) == v1, "Idempotent upload changed the result")
    upload(a, endpoint, title, "policy.txt", nine, key, 409)
    wait_status(a, base + "/1", "READY", 1)
    api(base, 404, b)
    api(base + "/1", 404, b)
    upload(b, base, None, "other.txt", nine, uuid.uuid4().hex, 404)
    parsed("tenant-b", document, 1, 404)
    content = parsed("tenant-a", document, 1)
    require(content["title"] == title and content["locations"]["unit"] == "line" and positions(content) == [
        (1, "入职满一年，可休 7 天公司年假。"), (3, "以现行版本为准。")], "TXT line locations differ")
    old = chunks("tenant-a", document, 1)
    require(len(old) == 1 and old[0]["_source"]["text"] == content["text"] and old[0]["_source"]["title"] == title
            and old[0]["_source"]["start"] == 1 and old[0]["_source"]["end"] == 3
            and len(old[0]["_source"]["embedding"]) == 128, "Version one index metadata differ")
    keyword = os_request(INDEX + "/_search", {"query": {"bool": {
        "filter": filters("tenant-a", document, 1), "must": {"match": {"text": "年假"}}}}})
    require(keyword["hits"]["total"]["value"] == 1, "The indexed Chinese keyword is not searchable")
    vector = os_request(INDEX + "/_search", {"size": 1, "query": {"knn": {"embedding": {
        "vector": old[0]["_source"]["embedding"], "k": 1, "filter": {"bool": {"filter": filters("tenant-a", document, 1)}}}}}})
    require([hit["_id"] for hit in vector["hits"]["hits"]] == [old[0]["_id"]], "The vector is not searchable")

    other = upload(b, endpoint, title, "policy.txt", "入职满一年，可休 13 天公司年假。".encode(), key)
    require(document != other["documentId"], "Idempotency keys leaked across tenants")
    wait_status(b, f"{endpoint}/{other['documentId']}/versions/1", "READY", 1)
    print("PASS: upload validation, idempotency, tenant boundary, TXT line locations and vectors", flush=True)

    compose("pause", "search-service")
    try:
        uploaded = upload(a, base, None, "policy.txt", nine, uuid.uuid4().hex)
        require(uploaded["version"] == 2, "Append did not create version two")
        wait_status(a, base + "/2", "INDEXING", 1)
        require(chunks("tenant-a", document, 1) == old and not chunks("tenant-a", document, 2),
                "Version one was not the only indexed version during the delay")
    finally:
        compose("unpause", "search-service")
    wait_status(a, base + "/2", "READY", 2)
    new = chunks("tenant-a", document, 2)
    require(len(new) == 1 and "9 天" in new[0]["_source"]["text"], "Version two is not searchable")
    rows = f"SELECT CONCAT(id,':',chunk_index) FROM search_db.doc_chunk WHERE document_id={int(document)} AND version=2;"
    before = mysql(rows).stdout
    publish(PARSED, outbox("doc_db", document, 2), count=2)
    wait_consumed("rag-search-index-v1", PARSED)
    require(mysql(rows).stdout == before and chunks("tenant-a", document, 2) == new, "Repeated events changed the chunks")

    mysql(f"UPDATE doc_db.kb_document_version SET status='INDEXING' WHERE document_id={int(document)} AND version=1;")
    wait_status(a, base + "/1", "INDEXING", 2)
    publish(RESULT, outbox("search_db", document, 1))
    wait_status(a, base + "/1", "READY", 2)
    require("7 天" in parsed("tenant-a", document, 1)["text"], "The original version is unavailable after replacement")
    print("PASS: delayed index, replayed parse events, late completion and preserved historical source", flush=True)

    for name, data, unit, expected in [
        ("paragraphs.docx", docx_fixture(), "paragraph", [(1, "第一段"), (3, "表格里的段落"), (4, "末尾段落")]),
        ("two-pages.pdf", pdf_fixture(), "page", [(1, "Vacation Policy"), (2, "Page Two")]),
    ]:
        upload_result = upload(a, endpoint, MARKER + name, name, data, uuid.uuid4().hex)
        identifier = upload_result["documentId"]
        wait_status(a, f"{endpoint}/{identifier}/versions/1", "READY", 1)
        content = parsed("tenant-a", identifier, 1)
        require(content["locations"]["unit"] == unit and positions(content) == expected, f"Position mismatch for {name}")
    unicode_line = "界" * 799 + "😀后"
    long_text = upload(a, endpoint, MARKER + "Unicode 切片", "unicode.txt", unicode_line.encode(), uuid.uuid4().hex)
    identifier = long_text["documentId"]
    wait_status(a, f"{endpoint}/{identifier}/versions/1", "READY", 1)
    slices = [hit["_source"] for hit in chunks("tenant-a", identifier, 1)]
    require(len(slices) == 2 and "".join(item["text"] for item in slices) == unicode_line
            and all(item["start"] == item["end"] == 1 for item in slices), "Chunking split a Unicode character or lost its line")
    scan = upload(a, base, None, "scan.pdf", pdf_fixture(scanned=True), uuid.uuid4().hex)
    require(scan["version"] == 3, "Unexpected scan version number")
    wait_status(a, base + "/3", "FAILED", 2, "NO_EXTRACTABLE_TEXT")
    print("PASS: DOCX paragraph and table locations, PDF page locations, image-only PDF failure", flush=True)

    dlt_offset = dlq_end()
    os_request(INDEX + "/_settings", {"index.blocks.write": True}, method="PUT")
    try:
        failure = upload(a, base, None, "policy.txt", nine, uuid.uuid4().hex)
        require(failure["version"] == 4, "Unexpected test failure version number")
        wait_status(a, base + "/4", "FAILED", 2, "INDEX_WRITE_FAILED")
        require(chunks("tenant-a", document, 2) == new and not chunks("tenant-a", document, 4),
                "An indexing failure changed the active version's index")
    finally:
        os_request(INDEX + "/_settings", {"index.blocks.write": None}, method="PUT")
    dead_letter = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                          "--topic", PARSED + ".DLT", "--partition", "0", "--offset", str(dlt_offset),
                          "--max-messages", "1", "--timeout-ms", "8000")
    dead = json.loads(dead_letter.stdout)
    require(dead["documentId"] == document and dead["version"] == 4, "Failed record did not enter the dead-letter topic")

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        submitted = sorted(item.result()["version"] for item in (
            pool.submit(upload, a, base, None, "parallel.txt", nine, uuid.uuid4().hex),
            pool.submit(upload, a, base, None, "parallel.txt", seven, uuid.uuid4().hex),
        ))
    require(submitted == [5, 6], "Concurrent appends selected the same version number")
    wait_status(a, base + "/6", "READY", 6)
    wait_status(a, base + "/5", "READY", 6)
    history = api(base + "?page=0&size=2", 200, a)
    require(history["hasNext"] and [item["version"] for item in history["items"]] == [6, 5], "Version history is inconsistent")
    print("PASS: actual OpenSearch write rejection, Kafka dead-letter recovery and concurrent appends", flush=True)


if __name__ == "__main__":
    try:
        check()
    finally:
        cleanup()
