package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.contract.ErrorCodes;

class AgentExecutionServiceAuditTest {

    @Test
    void preservesDeniedAuthorizationFromTheToolResultEvent() {
        ToolResultEndEvent event = event(
                ToolResultState.DENIED,
                Map.of(
                        ToolAuditMetadata.ERROR_CODE,
                        ErrorCodes.TOOL_DENIED,
                        ToolAuditMetadata.DECISION,
                        ToolAuditMetadata.DENIED));

        assertThat(AgentExecutionService.auditDecision(event, event.getState())).isEqualTo(ToolAuditMetadata.DENIED);
        assertThat(AgentExecutionService.auditErrorCode(event, event.getState()))
                .isEqualTo(ErrorCodes.TOOL_DENIED);
    }

    @Test
    void keepsAuthorizedExecutionFailureSeparateFromAuthorizationErrors() {
        ToolResultEndEvent event = event(
                ToolResultState.ERROR,
                Map.of(
                        ToolAuditMetadata.ERROR_CODE,
                        ErrorCodes.MCP_TOOL_ERROR,
                        ToolAuditMetadata.DECISION,
                        ToolAuditMetadata.ALLOW));

        assertThat(AgentExecutionService.auditDecision(event, event.getState())).isEqualTo(ToolAuditMetadata.ALLOW);
        assertThat(AgentExecutionService.auditErrorCode(event, event.getState()))
                .isEqualTo(ErrorCodes.MCP_TOOL_ERROR);
    }

    @Test
    void defaultsUnknownErrorToPendingInsteadOfAssumingAuthorization() {
        ToolResultEndEvent event = event(ToolResultState.ERROR, Map.of());

        assertThat(AgentExecutionService.auditDecision(event, event.getState())).isEqualTo(ToolAuditMetadata.PENDING);
    }

    private static ToolResultEndEvent event(ToolResultState state, Map<String, Object> metadata) {
        return new ToolResultEndEvent(
                "event-1", "2026-11-18T00:00:00Z", "reply-1", "call-1", "tool-1", state, metadata);
    }
}
