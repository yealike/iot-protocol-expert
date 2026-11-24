package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.UUID;

/** Control 为本地子 Run 冻结的父子关系和 MCP 工具范围。 */
public record RunLineage(
        UUID parentRunId, UUID rootRunId, UUID taskId, int depth, List<String> allowedTools, long maxTotalTokens) {}
