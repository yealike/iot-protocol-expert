package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.McpEndpointPolicy;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** MCP 不可变发布版本视图。 */
public record McpReleaseView(
        UUID id,
        UUID componentId,
        String componentKey,
        String version,
        String transport,
        McpEndpointPolicy endpointPolicy,
        UUID credentialId,
        List<McpToolDescriptor> tools,
        String catalogDigest,
        String contentDigest,
        String state,
        long generation,
        Instant createdAt) {

    public McpReleaseView {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static McpReleaseView from(McpRelease release) {
        if (release == null) {
            return null;
        }
        return new McpReleaseView(
                release.id(),
                release.componentId(),
                release.componentKey(),
                release.version(),
                release.transport(),
                endpointPolicy(release.endpointPolicy()),
                release.credentialId(),
                release.tools(),
                release.catalogDigest(),
                release.contentDigest(),
                release.state(),
                release.generation(),
                release.createdAt());
    }

    private static McpEndpointPolicy endpointPolicy(java.util.Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return JsonSupport.read(JsonSupport.write(values), McpEndpointPolicy.class);
    }
}
