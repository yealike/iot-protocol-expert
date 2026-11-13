package top.yealike.ipe.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ai.agent.AgentCatalog;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.knowledge.KnowledgeService;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.tool.RemoteTools;
import top.yealike.ipe.ai.tool.ToolGuard;
import top.yealike.ipe.ai.tool.ToolSession;

class FallbackAnswerServiceTest {

    @Test
    void specializedAgentCannotUseAnotherAgentsToolsDuringFallback() {
        PolicyService policy = mock(PolicyService.class);
        when(policy.canUseTool(any(), anyString(), anyString()))
                .thenReturn(new PolicyService.Decision(true, "OK", "v1"));
        var service = new FallbackAnswerService(
                mock(ToolGuard.class), mock(RemoteTools.class), mock(KnowledgeService.class), policy);
        var definition =
                new AgentCatalog.Definition("protocol-expert", "v1", "协议", "AGENT", "", "", "", Set.of(), "PUBLISHED");
        var session = new ToolSession(
                new UserIdentity("1", "user", List.of("ADMIN"), List.of("ai:use", "ai:admin"), 0),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "protocol-expert",
                "v1",
                "*",
                4);
        var run = mock(RunRecords.Run.class);
        when(run.question()).thenReturn("订单 202411130001 为什么停止");
        assertFalse(service.answer(run, session, definition).contains("订单详情"));
    }

    @Test
    void extractsNumericOrderNumber() {
        assertEquals("202411130001", FallbackAnswerService.extractOrderNo("订单 202411130001 为什么停止"));
    }

    @Test
    void ignoresShortCommandCodes() {
        assertNull(FallbackAnswerService.extractOrderNo("0x3B 是什么含义"));
    }

    @Test
    void extractsHexCommandCode() {
        assertEquals("0x3B", FallbackAnswerService.extractHex("请解释 0x3B 停止原因"));
        assertNull(FallbackAnswerService.extractHex("没有命令字"));
    }
}
