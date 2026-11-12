package top.yealike.ipe.ops.api.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ops/protocol-documents")
public class ProtocolDocumentController {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final Path root;

    public ProtocolDocumentController(JdbcTemplate jdbc, @Value("${ipe.ops.protocol-document-dir:./data/protocol-documents}") String directory) {
        this.jdbc = jdbc;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String protocol) {
        return jdbc.queryForList("select id, protocol_code as \"protocolCode\", protocol_name_en as \"protocolNameEn\", document_version as \"documentVersion\", title, original_filename as \"originalFilename\", file_size as \"fileSize\", status, uploaded_by as \"uploadedBy\", created_at as \"createdAt\" from ipe_ops.protocol_document where status = 'ACTIVE' and (? = '' or protocol_code = ?) order by id desc",
                protocol == null ? "" : protocol, protocol == null ? "" : protocol);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> upload(@RequestParam String protocolCode, @RequestParam String protocolNameEn, @RequestParam String documentVersion,
            @RequestParam String title, @RequestParam MultipartFile file, Principal principal) throws Exception {
        if (protocolCode == null || protocolCode.isBlank() || protocolCode.trim().length() > 32) throw new IllegalArgumentException("协议名称不能为空且不能超过 32 字符");
        protocolCode = protocolCode.trim();
        if (protocolNameEn == null || protocolNameEn.isBlank() || protocolNameEn.trim().length() > 64 || !protocolNameEn.trim().matches("[A-Za-z0-9][A-Za-z0-9 ._-]*")) throw new IllegalArgumentException("英文名称只能包含英文字母、数字、空格及 ._-，且不能超过 64 字符");
        protocolNameEn = protocolNameEn.trim();
        if (title.isBlank() || title.length() > 256 || documentVersion.isBlank() || documentVersion.length() > 64)
            throw new IllegalArgumentException("标题或版本不合法");
        String original = file.getOriginalFilename();
        if (original == null) throw new IllegalArgumentException("缺少文件名");
        original = original.replace('\\', '/');
        original = original.substring(original.lastIndexOf('/') + 1);
        String lower = original.toLowerCase(java.util.Locale.ROOT);
        if (original.length() > 255 || !(lower.endsWith(".pdf") || lower.endsWith(".md") || lower.endsWith(".txt")))
            throw new IllegalArgumentException("仅支持 PDF、Markdown 和文本文件");
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件大小须在 1 字节至 20 MB 之间");
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        String key = UUID.randomUUID().toString();
        Path temp = Files.createTempFile(realRoot, "upload-", ".tmp");
        Path target = realRoot.resolve(key).normalize();
        try {
            try (var in = file.getInputStream()) { Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING); }
            if (Files.size(temp) != file.getSize()) throw new IOException("文件大小不一致");
            try (var in = Files.newInputStream(temp)) {
                byte[] head = in.readNBytes(5);
                if (lower.endsWith(".pdf") && !java.util.Arrays.equals(head, new byte[] {'%', 'P', 'D', 'F', '-'}))
                    throw new IllegalArgumentException("文件内容不是有效的 PDF");
                if (!lower.endsWith(".pdf")) {
                    for (byte b : head) if (b == 0) throw new IllegalArgumentException("文件内容不是文本");
                }
            }
            byte[] hash;
            try (var in = Files.newInputStream(temp)) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
                hash = digest.digest();
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            String type = lower.endsWith(".pdf") ? "application/pdf" : "text/plain";
            Long id = jdbc.queryForObject("insert into ipe_ops.protocol_document(protocol_code, protocol_name_en, document_version, title, original_filename, storage_key, content_type, file_size, sha256, uploaded_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", Long.class,
                    protocolCode, protocolNameEn, documentVersion.trim(), title.trim(), original, key, type, file.getSize(), HexFormat.of().formatHex(hash), principal.getName());
            return Map.of("id", id);
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            Files.deleteIfExists(target);
            throw e;
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable long id) throws IOException {
        return fileResponse(id, false);
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<FileSystemResource> view(@PathVariable long id) throws IOException {
        return fileResponse(id, true);
    }

    private ResponseEntity<FileSystemResource> fileResponse(long id, boolean inline) throws IOException {
        var rows = jdbc.queryForList("select storage_key, original_filename, content_type from ipe_ops.protocol_document where id = ? and status = 'ACTIVE'", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("文档不存在");
        var row = rows.get(0);
        String key = (String) row.get("storage_key");
        if (!key.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("文件标识无效");
        Path realRoot = root.toRealPath();
        Path path = realRoot.resolve(key).toRealPath();
        if (!path.startsWith(realRoot) || !Files.isRegularFile(path)) throw new IllegalArgumentException("文档文件不可用");
        String type = (String) row.get("content_type");
        MediaType mediaType = "application/pdf".equals(type) ? MediaType.APPLICATION_PDF : new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(mediaType)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename((String) row.get("original_filename"), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void archive(@PathVariable long id) {
        var document = jdbc.queryForList("select id from ipe_ops.protocol_document where id = ? and status = 'ACTIVE' for update", id);
        if (document.isEmpty()) throw new IllegalArgumentException("文档不存在");
        Integer references = jdbc.queryForObject("select count(*) from ipe_ops.product where protocol_document_id = ?", Integer.class, id);
        if (references != null && references > 0) throw new IllegalArgumentException("该文档已被产品引用，不能归档");
        if (jdbc.update("update ipe_ops.protocol_document set status = 'ARCHIVED', updated_at = now() where id = ? and status = 'ACTIVE'", id) != 1)
            throw new IllegalArgumentException("文档不存在");
    }
}
