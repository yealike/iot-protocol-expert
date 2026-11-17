package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.Map;

/** MCP 精确版本描述：入口由发现结果注入，描述本身不保存可变的重定向地址。 */
public record McpDescriptor(
        String key,
        String version,
        String transport,
        String endpoint,
        String credentialRef,
        long secretRevision,
        String catalogDigest,
        List<McpToolDescriptor> tools,
        Map<String, Object> endpointPolicy) {

    public McpDescriptor {
        tools = tools == null ? List.of() : List.copyOf(tools);
        endpointPolicy = endpointPolicy == null ? Map.of() : Map.copyOf(endpointPolicy);
    }
}
