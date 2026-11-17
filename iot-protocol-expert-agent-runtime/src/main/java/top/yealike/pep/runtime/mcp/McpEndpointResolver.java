package top.yealike.pep.runtime.mcp;

import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.contract.ComponentEndpoint;
import top.yealike.pep.runtime.contract.McpDescriptor;

/** MCP 入口由 Control 发布清单固定；Nacos 实例变化不得改变已发布工具的入口。 */
@Component
public class McpEndpointResolver {
    public McpDescriptor resolve(McpDescriptor descriptor) {
        if (descriptor != null) ComponentEndpoint.parse(descriptor.endpoint());
        return descriptor;
    }
}
