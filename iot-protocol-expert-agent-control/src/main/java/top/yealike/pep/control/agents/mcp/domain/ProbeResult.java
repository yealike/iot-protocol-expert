package top.yealike.pep.control.agents.mcp.domain;

import java.util.List;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

public record ProbeResult(
        String serverName,
        String serverVersion,
        String protocolVersion,
        List<McpToolDescriptor> tools,
        long latencyMs) {}
