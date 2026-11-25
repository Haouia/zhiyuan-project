package com.ragknowledge.doc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class DocumentUploads {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[!-~]{1,128}");

    private final JdbcClient jdbc;

    DocumentUploads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Accepted accept(String tenant, String key, Long documentId, String title, MultipartFile file) {
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw DocumentProblem.badRequest("Idempotency-Key 应为 1 到 128 个可见 ASCII 字符");
        }
        Upload upload = read(file);
        byte[] hash = fingerprint(documentId, title, upload);
        jdbc.sql("""
                INSERT INTO doc_db.upload_request(tenant_id, idempotency_key, request_hash) VALUES (:tenant, :key, :hash)
                ON DUPLICATE KEY UPDATE idempotency_key = idempotency_key
                """)
                .param("tenant", tenant).param("key", key).param("hash", hash).update();
        Request saved = jdbc.sql("""
                SELECT request_hash, document_id, version FROM doc_db.upload_request
                WHERE tenant_id = :tenant AND idempotency_key = :key FOR UPDATE
                """)
                .param("tenant", tenant).param("key", key)
                .query((row, index) -> new Request(row.getBytes("request_hash"), row.getObject("document_id", Long.class), row.getInt("version")))
                .single();
        if (!MessageDigest.isEqual(hash, saved.hash())) {
            throw new DocumentProblem(409, "IDEMPOTENCY_CONFLICT", "这个幂等键已用于另一份上传请求");
        }
        if (saved.documentId() != null) {
            return new Accepted(saved.documentId().toString(), saved.version(), "PENDING");
        }

        int version = 1;
        if (documentId == null) {
            var generated = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO doc_db.kb_document(tenant_id, title, latest_version) VALUES (:tenant, :title, 1)")
                    .param("tenant", tenant).param("title", title).update(generated, "id");
            documentId = generated.getKey().longValue();
        } else {
            version = jdbc.sql("SELECT latest_version FROM doc_db.kb_document WHERE id = :id AND tenant_id = :tenant FOR UPDATE")
                    .param("id", documentId).param("tenant", tenant).query(Integer.class).optional()
                    .orElseThrow(DocumentProblem::notFound) + 1;
            jdbc.sql("UPDATE doc_db.kb_document SET latest_version = :version WHERE id = :id AND tenant_id = :tenant")
                    .param("version", version).param("id", documentId).param("tenant", tenant).update();
        }
        jdbc.sql("""
                INSERT INTO doc_db.kb_document_version(document_id, version, filename, content_type, original_bytes)
                VALUES (:id, :version, :filename, :contentType, :bytes)
                """)
                .param("id", documentId).param("version", version).param("filename", upload.filename())
                .param("contentType", upload.contentType()).param("bytes", upload.bytes()).update();
        jdbc.sql("""
                UPDATE doc_db.upload_request SET document_id = :id, version = :version
                WHERE tenant_id = :tenant AND idempotency_key = :key
                """)
                .param("id", documentId).param("version", version).param("tenant", tenant).param("key", key).update();
        return new Accepted(documentId.toString(), version, "PENDING");
    }

    private Upload read(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) {
            throw new DocumentProblem(413, "FILE_TOO_LARGE", "单个文件最大为 10 MiB");
        }
        if (file.isEmpty() || file.getOriginalFilename() == null) {
            throw DocumentProblem.badRequest("请选择非空文件");
        }
        String filename = file.getOriginalFilename().replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).strip();
        if (filename.isEmpty() || filename.codePointCount(0, filename.length()) > 255
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw DocumentProblem.badRequest("文件名无效");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw DocumentProblem.badRequest("无法读取上传的文件");
        }
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx") && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
            return new Upload(filename, DocumentParser.DOCX, bytes);
        }
        if (name.endsWith(".pdf") && new String(bytes, 0, Math.min(1024, bytes.length), StandardCharsets.ISO_8859_1).contains("%PDF-")) {
            return new Upload(filename, DocumentParser.PDF, bytes);
        }
        if (name.endsWith(".txt")) {
            try {
                var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                if (text.chars().noneMatch(value -> value == 0)) {
                    return new Upload(filename, DocumentParser.TXT, bytes);
                }
            } catch (CharacterCodingException ignored) {
                // The API reports invalid UTF-8 as an unsupported file format.
            }
        }
        throw new DocumentProblem(415, "UNSUPPORTED_FORMAT", "请上传 DOCX、PDF 或 UTF-8 TXT 文件");
    }

    private byte[] fingerprint(Long documentId, String title, Upload upload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((documentId + "\0" + title + "\0" + upload.filename() + "\0" + upload.contentType() + "\0")
                    .getBytes(StandardCharsets.UTF_8));
            return digest.digest(upload.bytes());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Accepted(String documentId, int version, String status) {}

    private record Upload(String filename, String contentType, byte[] bytes) {}

    private record Request(byte[] hash, Long documentId, int version) {}
}
