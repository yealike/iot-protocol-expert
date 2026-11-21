package top.yealike.pep.ops.api.internal;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.pep.ops.devices.mapper.ProtocolDocumentMapper;

/** Control 知识索引使用的受服务令牌保护的协议文档读取入口。 */
@RestController
@RequestMapping(path = "/internal/protocol-documents", produces = "application/json")
public class InternalProtocolDocumentController {

    private static final int CHUNK_BYTES = 128 * 1024;
    private static final java.util.regex.Pattern STORAGE_KEY = java.util.regex.Pattern.compile("[0-9a-f-]{36}");

    private final ProtocolDocumentMapper documents;
    private final String documentDirectory;

    public InternalProtocolDocumentController(
            ProtocolDocumentMapper documents,
            @Value("${ipe.ops.protocol-document-dir:./data/protocol-documents}") String documentDirectory) {
        this.documents = documents;
        this.documentDirectory = documentDirectory;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return documents.selectActiveForAi();
    }

    @GetMapping("/{id}/content")
    public Map<String, Object> content(@PathVariable long id, @RequestParam(defaultValue = "0") long offset) {
        if (id <= 0 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String storageKey = documents.selectActiveStorageKey(id);
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path file;
        try {
            Path root = Paths.get(documentDirectory).toRealPath();
            file = root.resolve(storageKey).toRealPath();
            if (!file.startsWith(root)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            if (!Files.isRegularFile(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try (var channel = Files.newByteChannel(file)) {
            long size = channel.size();
            if (offset > size) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            channel.position(offset);
            try (var stream = Channels.newInputStream(channel)) {
                byte[] bytes = stream.readNBytes(CHUNK_BYTES);
                return Map.of(
                        "base64",
                        Base64.getEncoder().encodeToString(bytes),
                        "size",
                        size,
                        "offset",
                        offset,
                        "eof",
                        offset + bytes.length == size);
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }
}
