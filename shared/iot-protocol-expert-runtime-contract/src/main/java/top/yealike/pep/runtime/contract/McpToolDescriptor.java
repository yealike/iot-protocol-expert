package top.yealike.pep.runtime.contract;

import java.util.Map;

/** 冻结的 MCP 工具描述。schemaDigest 用于发布后检测协议漂移。 */
public record McpToolDescriptor(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> annotations,
        String schemaDigest,
        boolean readOnly) {

    public McpToolDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }
}
