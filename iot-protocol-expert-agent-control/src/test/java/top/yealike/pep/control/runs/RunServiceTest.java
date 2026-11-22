package top.yealike.pep.control.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.access.policy.domain.Decision;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.config.properties.PlatformRuntime;
import top.yealike.pep.control.conversations.ConversationRepository;
import top.yealike.pep.control.conversations.domain.Conversation;
import top.yealike.pep.control.integration.runtime.RuntimeDispatchClient;
import top.yealike.pep.control.integration.runtime.dto.RuntimeTarget;
import top.yealike.pep.control.observability.AiObservability;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.UserIdentity;

class RunServiceTest {

    @Test
    void submitRejectsUnsupportedPriority() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunEventService events = mock(RunEventService.class);
        PolicyService policy = mock(PolicyService.class);
        AgentService agents = mock(AgentService.class);
        RuntimeDispatchClient runtime = mock(RuntimeDispatchClient.class);
        AiObservability observability = mock(AiObservability.class);
        passThroughObservability(observability);
        PlatformProperties platform = platform(100, 2, 1);
        when(policy.canUseAgent(identity(), "agent")).thenReturn(new Decision(true, null, "v1"));
        UUID conversationId = UUID.randomUUID();
        when(conversations.find(conversationId)).thenReturn(java.util.Optional.of(conversation(conversationId)));
        RunService service = new RunService(
                conversations,
                runs,
                events,
                policy,
                agents,
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                observability,
                platform);

        assertThatThrownBy(() -> service.submit(identity(), conversationId, "问题", "key", 200))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("不支持优先级控制");

        verifyNoInteractions(runs);
    }

    @Test
    void submitRejectsWhenUserQueueQuotaIsFull() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunEventService events = mock(RunEventService.class);
        PolicyService policy = mock(PolicyService.class);
        AgentService agents = mock(AgentService.class);
        RuntimeDispatchClient runtime = mock(RuntimeDispatchClient.class);
        AiObservability observability = mock(AiObservability.class);
        passThroughObservability(observability);
        UUID conversationId = UUID.randomUUID();
        when(conversations.find(conversationId)).thenReturn(java.util.Optional.of(conversation(conversationId)));
        when(policy.canUseAgent(identity(), "agent")).thenReturn(new Decision(true, null, "v1"));
        when(runs.findByIdempotency(conversationId, "key")).thenReturn(java.util.Optional.empty());
        when(runs.countQueued()).thenReturn(0);
        when(runs.countUserQueued("user", null)).thenReturn(2);
        when(runs.countUserActive("user", null)).thenReturn(1);
        RunService service = new RunService(
                conversations,
                runs,
                events,
                policy,
                agents,
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                observability,
                platform(100, 2, 1));

        assertThatThrownBy(() -> service.submit(identity(), conversationId, "问题", "key", null))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("排队任务已达上限");
    }

    @Test
    void idempotentRetryDoesNotConsumeOrCheckQuotaAgain() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunEventService events = mock(RunEventService.class);
        PolicyService policy = mock(PolicyService.class);
        AgentService agents = mock(AgentService.class);
        RuntimeDispatchClient runtime = mock(RuntimeDispatchClient.class);
        AiObservability observability = mock(AiObservability.class);
        passThroughObservability(observability);
        UUID conversationId = UUID.randomUUID();
        Run existing = mock(Run.class);
        when(conversations.find(conversationId)).thenReturn(java.util.Optional.of(conversation(conversationId)));
        when(policy.canUseAgent(identity(), "agent")).thenReturn(new Decision(true, null, "v1"));
        when(runs.findByIdempotency(conversationId, "key")).thenReturn(java.util.Optional.of(existing));
        RunService service = new RunService(
                conversations,
                runs,
                events,
                policy,
                agents,
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                observability,
                platform(100, 2, 1));

        assertThat(service.submit(identity(), conversationId, "问题", "key", null))
                .isSameAs(existing);
        verify(runs).findByIdempotency(conversationId, "key");
        verify(runs, org.mockito.Mockito.never()).lockQuota(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void submitAllowsOneActiveAndTwoQueuedRuns() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunEventService events = mock(RunEventService.class);
        PolicyService policy = mock(PolicyService.class);
        AgentService agents = mock(AgentService.class);
        RuntimeDispatchClient runtime = mock(RuntimeDispatchClient.class);
        AiObservability observability = mock(AiObservability.class);
        passThroughObservability(observability);
        UUID conversationId = UUID.randomUUID();
        when(conversations.find(conversationId)).thenReturn(java.util.Optional.of(conversation(conversationId)));
        when(policy.canUseAgent(identity(), "agent")).thenReturn(new Decision(true, null, "v1"));
        when(runs.findByIdempotency(conversationId, "key")).thenReturn(java.util.Optional.empty());
        when(runs.countQueued()).thenReturn(0);
        when(runs.countUserQueued("user", null)).thenReturn(1);
        when(runs.countUserActive("user", null)).thenReturn(1);
        when(agents.requirePublishedRelease("agent", "1.0.0")).thenThrow(AiExceptions.unavailable("已越过排队配额"));
        RunService service = new RunService(
                conversations,
                runs,
                events,
                policy,
                agents,
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                observability,
                platform(100, 2, 1));

        assertThatThrownBy(() -> service.submit(identity(), conversationId, "问题", "key", null))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessage("已越过排队配额");
    }

    @Test
    void submitRejectsWhenActiveAndQueuedReachCombinedLimit() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunEventService events = mock(RunEventService.class);
        PolicyService policy = mock(PolicyService.class);
        AgentService agents = mock(AgentService.class);
        RuntimeDispatchClient runtime = mock(RuntimeDispatchClient.class);
        AiObservability observability = mock(AiObservability.class);
        passThroughObservability(observability);
        UUID conversationId = UUID.randomUUID();
        when(conversations.find(conversationId)).thenReturn(java.util.Optional.of(conversation(conversationId)));
        when(policy.canUseAgent(identity(), "agent")).thenReturn(new Decision(true, null, "v1"));
        when(runs.findByIdempotency(conversationId, "key")).thenReturn(java.util.Optional.empty());
        when(runs.countQueued()).thenReturn(0);
        when(runs.countUserQueued("user", null)).thenReturn(0);
        when(runs.countUserActive("user", null)).thenReturn(3);
        RunService service = new RunService(
                conversations,
                runs,
                events,
                policy,
                agents,
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                observability,
                platform(100, 2, 1));

        assertThatThrownBy(() -> service.submit(identity(), conversationId, "问题", "key", null))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("未完成任务已达上限");
    }

    @Test
    void retryArrivingAfterQuotaLockReturnsExistingWithoutCheckingQuota() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunEventService events = mock(RunEventService.class);
        PolicyService policy = mock(PolicyService.class);
        AgentService agents = mock(AgentService.class);
        RuntimeDispatchClient runtime = mock(RuntimeDispatchClient.class);
        AiObservability observability = mock(AiObservability.class);
        passThroughObservability(observability);
        UUID conversationId = UUID.randomUUID();
        Run existing = mock(Run.class);
        when(conversations.find(conversationId)).thenReturn(java.util.Optional.of(conversation(conversationId)));
        when(policy.canUseAgent(identity(), "agent")).thenReturn(new Decision(true, null, "v1"));
        when(runs.findByIdempotency(conversationId, "key"))
                .thenReturn(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(existing));
        RunService service = new RunService(
                conversations,
                runs,
                events,
                policy,
                agents,
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                observability,
                platform(100, 2, 1));

        assertThat(service.submit(identity(), conversationId, "问题", "key", null))
                .isSameAs(existing);
        verify(runs).lockQuota("ai-run-quota:global");
        verify(runs).lockQuota("ai-run-quota:user:user");
        verify(runs, org.mockito.Mockito.never()).countQueued();
        verify(runs, org.mockito.Mockito.never()).countUserActive("user", null);
    }

    @Test
    void cancelsAnAcceptedRunThroughTheConfiguredRuntimeAddress() {
        var conversations = mock(ConversationRepository.class);
        var runs = mock(RunRepository.class);
        var policy = mock(PolicyService.class);
        var runtime = mock(RuntimeDispatchClient.class);
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var identity = identity();
        var run = mock(Run.class);
        var target = new RuntimeTarget("http://localhost:19084", "direct", "direct");
        when(runs.find(runId)).thenReturn(Optional.of(run));
        when(run.conversationId()).thenReturn(conversationId);
        when(run.agentKey()).thenReturn("agent");
        when(run.status()).thenReturn(RunStatus.CANCEL_REQUESTED);
        when(run.runtimeInstanceId()).thenReturn("runtime-1");
        when(run.executionToken()).thenReturn(7L);
        when(conversations.find(conversationId)).thenReturn(Optional.of(conversation(conversationId)));
        when(policy.canUseAgent(identity, "agent")).thenReturn(new Decision(true, null, "v1"));
        when(runtime.targets()).thenReturn(List.of(target));
        var service = new RunService(
                conversations,
                runs,
                mock(RunEventService.class),
                policy,
                mock(AgentService.class),
                runtime,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(AiObservability.class),
                platform(100, 2, 1));

        assertThat(service.cancel(identity, runId)).isEqualTo(RunStatus.CANCEL_REQUESTED);

        verify(runtime).cancel(target, runId, 7L);
    }

    private static PlatformProperties platform(int queueLimit, int perUserQueued, int perUserInFlight) {
        return new PlatformProperties(
                null, null, new PlatformRuntime(3, 30, queueLimit, perUserQueued, perUserInFlight, null), null);
    }

    private static void passThroughObservability(AiObservability observability) {
        when(observability.observeSupplier(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
    }

    private static UserIdentity identity() {
        return new UserIdentity("user", "user", java.util.List.of("USER"), java.util.List.of(), 1L);
    }

    private static Conversation conversation(UUID id) {
        return new Conversation(
                id, "user", "org", "title", "agent", "1.0.0", null, null, "ACTIVE", 0L, null, null, null);
    }
}
