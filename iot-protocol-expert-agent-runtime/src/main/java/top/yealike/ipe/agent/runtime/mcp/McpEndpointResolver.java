package top.yealike.ipe.agent.runtime.mcp;

import org.springframework.stereotype.Component;
import top.yealike.ipe.agent.runtime.registry.NacosRuntimeRegistry;
import top.yealike.ipe.ai.contract.McpDescriptor;

/**
 * 每次工具调用前从注册中心解析 MCP 当前健康入口。
 *
 * <p>发布描述中的 endpoint 只作为注册中心关闭时的显式本地回退；开启注册中心后，地址切换不需要重新发布 Agent。
 */
@Component
public class McpEndpointResolver {

    private final NacosRuntimeRegistry registry;

    public McpEndpointResolver(NacosRuntimeRegistry registry) {
        this.registry = registry;
    }

    public McpDescriptor resolve(McpDescriptor descriptor) {
        if (descriptor == null || !registry.enabled()) {
            return descriptor;
        }
        String serviceName = text(descriptor.endpointPolicy().get("serviceName"));
        if (serviceName.isBlank()) {
            return descriptor;
        }
        String baseUrl = registry.discoverBaseUrl(serviceName);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("MCP 注册中心没有健康实例：" + serviceName);
        }
        String path = text(descriptor.endpointPolicy().get("path"));
        return new McpDescriptor(
                descriptor.key(),
                descriptor.version(),
                descriptor.transport(),
                baseUrl + path,
                descriptor.credentialRef(),
                descriptor.secretRevision(),
                descriptor.catalogDigest(),
                descriptor.tools(),
                descriptor.endpointPolicy());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
