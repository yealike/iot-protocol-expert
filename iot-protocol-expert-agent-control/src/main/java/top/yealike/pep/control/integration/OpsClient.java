package top.yealike.pep.control.integration;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.config.properties.AiProperties;
import top.yealike.pep.control.config.properties.PlatformIdentity;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.UserIdentity;

/** Typed access to MCP capabilities used by application code, outside the model's tool list. */
@Component
public class OpsClient {
    private final RestClient http;
    private final String serviceToken;
    private final int maxDocumentBytes;

    public OpsClient(RestClient.Builder builder, AiProperties properties, PlatformProperties platformProperties) {
        PlatformIdentity identity = platformProperties.identity();
        if (identity == null || identity.baseUrl() == null || identity.baseUrl().isBlank()) {
            throw new IllegalStateException("缺少 IPE_OPS_BASE_URL");
        }
        serviceToken = identity.serviceToken();
        http = builder.baseUrl(identity.baseUrl()).build();
        maxDocumentBytes = properties.knowledge().maxDocumentBytes();
    }

    public List<Map<String, Object>> documents(UserIdentity identity) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = http.get()
                .uri("/internal/protocol-documents")
                .header("X-Ops-Service-Token", requireServiceToken())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(List.class);
        return rows == null ? List.of() : rows;
    }

    public byte[] documentContent(UserIdentity identity, String documentId) {
        var output = new ByteArrayOutputStream();
        long expectedSize = -1;
        while (true) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = http.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/protocol-documents/{id}/content")
                            .queryParam("offset", output.size())
                            .build(Long.parseLong(documentId)))
                    .header("X-Ops-Service-Token", requireServiceToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            if (result == null) {
                throw AiExceptions.upstream("协议文档分块读取失败");
            }
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

    private String requireServiceToken() {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw AiExceptions.upstream("未配置 IPE_OPS_SERVICE_TOKEN");
        }
        return serviceToken;
    }
}
