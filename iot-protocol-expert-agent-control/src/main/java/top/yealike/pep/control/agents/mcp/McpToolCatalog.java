package top.yealike.pep.control.agents.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/**
 * 用官方 SDK 的游标接口读取全量工具目录。
 *
 * <p>SDK 的 {@code listTools} 只返回一页，自动分页、游标防环、数量上限和总超时仍需调用方负责。
 */
final class McpToolCatalog {

    static final int MAX_PAGES = 20;
    static final int MAX_TOOLS = 200;

    private McpToolCatalog() {}

    static List<McpToolDescriptor> readAll(McpSyncClient client, long deadlineNanos) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> names = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw failure("MCP 探测超时");
            }
            McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
            if (result.tools() != null) {
                for (McpSchema.Tool tool : result.tools()) {
                    if (tool == null || tool.name() == null || tool.name().isBlank()) {
                        throw failure("MCP 返回了无名称的工具");
                    }
                    if (!names.add(tool.name())) {
                        throw failure("MCP 工具名称重复：" + tool.name());
                    }
                    tools.add(tool);
                    if (tools.size() > MAX_TOOLS) {
                        throw failure("MCP 工具数量超过 200 上限");
                    }
                }
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                return tools.stream().map(McpToolDescriptors::describe).toList();
            }
            if (!cursors.add(cursor)) {
                throw failure("MCP 工具分页游标重复");
            }
        }
        throw failure("MCP 工具分页超过 20 页上限");
    }

    private static AiExceptions.AiException failure(String message) {
        return AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, message);
    }
}
