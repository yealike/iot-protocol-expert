package top.yealike.ipe.ai.gateway;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.support.AiExceptions;

/** Typed access to MCP capabilities used by application code, outside the model's tool list. */
@Component
public class OpsClient {
    private final McpGateway gateway;
    private final int maxDocumentBytes;

    public OpsClient(McpGateway gateway, AiProperties properties) {
        this.gateway = gateway;
        maxDocumentBytes = properties.knowledge().maxDocumentBytes();
    }

    public List<Map<String, Object>> documents(UserIdentity identity) {
        Object data = gateway.call("list_documents", Map.of(), actor(identity)).get("data");
        if (!(data instanceof List<?> rows)) throw AiExceptions.upstream("协议文档列表读取失败");
        return rows.stream().map(McpGateway::object).toList();
    }

    public byte[] documentContent(UserIdentity identity, String documentId) {
        var output = new ByteArrayOutputStream();
        long expectedSize = -1;
        while (true) {
            var result = gateway.call(
                    "read_document",
                    Map.of("id", Long.parseLong(documentId), "offset", output.size()),
                    actor(identity));
            long size = ((Number) result.get("size")).longValue();
            long offset = ((Number) result.get("offset")).longValue();
            if (size > maxDocumentBytes) throw new IllegalArgumentException("协议文档超过索引大小上限");
            if (offset != output.size() || size < offset || (expectedSize >= 0 && size != expectedSize)) {
                throw AiExceptions.upstream("协议文档分块不一致");
            }
            expectedSize = size;
            byte[] bytes = Base64.getDecoder().decode((String) result.get("base64"));
            boolean eof = Boolean.TRUE.equals(result.get("eof"));
            if (bytes.length > size - offset || eof != (offset + bytes.length == size) || (!eof && bytes.length == 0)) {
                throw AiExceptions.upstream("协议文档分块无效");
            }
            output.writeBytes(bytes);
            if (eof) return output.toByteArray();
        }
    }

    private static Map<String, String> actor(UserIdentity identity) {
        return Map.of("X-Ai-Actor", identity.actor());
    }
}
