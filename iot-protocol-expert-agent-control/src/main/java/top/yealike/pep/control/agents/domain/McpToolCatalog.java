package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** MCP 当前测试目录与最近发布目录的合并视图。 */
public record McpToolCatalog(
        String source,
        UUID reportId,
        Boolean success,
        Instant testedAt,
        List<McpToolDescriptor> tools,
        List<String> warnings,
        String publishedVersion,
        List<McpToolDescriptor> publishedTools,
        String catalogDigest) {

    public McpToolCatalog {
        tools = tools == null ? List.of() : List.copyOf(tools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        publishedTools = publishedTools == null ? List.of() : List.copyOf(publishedTools);
    }
}
