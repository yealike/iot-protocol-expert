package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** MCP 连通性测试结果。 */
public record McpTestResult(
        String endpoint,
        String serverName,
        String serverVersion,
        String protocolVersion,
        List<McpToolDescriptor> tools,
        Long latencyMs,
        List<String> warnings,
        boolean success,
        UUID reportId,
        String configDigest,
        Instant testedAt,
        String errorCode,
        String message) {

    public McpTestResult {
        tools = tools == null ? List.of() : List.copyOf(tools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
