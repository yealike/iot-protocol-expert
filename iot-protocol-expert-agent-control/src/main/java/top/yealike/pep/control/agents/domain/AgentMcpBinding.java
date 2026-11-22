package top.yealike.pep.control.agents.domain;

import java.util.UUID;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** 控制面领域记录。 */
public record AgentMcpBinding(
        UUID mcpReleaseId,
        String componentKey,
        String version,
        String toolName,
        boolean required,
        int executionOrder,
        McpToolDescriptor descriptor) {}
