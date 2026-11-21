package top.yealike.pep.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.ToolAuthorizeResponse;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.execution.RunGuard;
import top.yealike.pep.runtime.execution.ToolAuditMetadata;
import top.yealike.pep.runtime.observability.RuntimeObservability;
import top.yealike.pep.runtime.prepare.PreparedTool;

class McpAgentToolTest {

    @Test
    void authorizationRejectionProducesDeniedStateAndAuditMetadata() {
        ControlClient control = mock(ControlClient.class);
        when(control.authorize(any()))
                .thenReturn(new ToolAuthorizeResponse(false, ErrorCodes.TOOL_DENIED, "当前账号无权调用该工具", null, 7L));
        RuntimeObservability observability = mock(RuntimeObservability.class);
        when(observability.observeSupplier(anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            return operation.get();
        });
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "get_device_detail", "device detail", Map.of(), Map.of(), Map.of(), "digest", true);
        PreparedTool prepared =
                new PreparedTool("mcp_ops__get_device_detail", "ops", "1.0.0", descriptor.name(), descriptor);
        McpDescriptor mcp = new McpDescriptor(
                "ops", "1.0.0", "streamable-http", null, null, 0, "catalog", List.of(descriptor), Map.of());
        RunGuard guard = new RunGuard(UUID.randomUUID(), 9L, 7L, Instant.now().plusSeconds(30), 2);
        McpAgentTool tool = new McpAgentTool(
                prepared,
                mcp,
                guard,
                control,
                mock(McpConnectionPool.class),
                mock(McpEndpointResolver.class),
                new SimpleMeterRegistry(),
                observability);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("deviceId", "device-1"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.DENIED);
        assertThat(result.getMetadata())
                .containsEntry(ToolAuditMetadata.DECISION, ToolAuditMetadata.DENIED)
                .containsEntry(ToolAuditMetadata.ERROR_CODE, ErrorCodes.TOOL_DENIED);
    }
}
