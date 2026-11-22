package top.yealike.pep.control.runs.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.model.GatewayTokenIssuer;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.config.properties.AiProperties;
import top.yealike.pep.control.config.properties.AiRun;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.config.properties.PlatformRuntime;
import top.yealike.pep.control.conversations.ConversationRepository;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.runs.RunEventService;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.CredentialResolveRequest;
import top.yealike.pep.runtime.contract.RunCompletionRequest;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;
import top.yealike.pep.runtime.contract.RunTerminalStatus;
import top.yealike.pep.runtime.contract.ToolAuthorizeRequest;

class RuntimeRunCoordinatorExecutionGuardTest {

    private final RunRepository runs = mock(RunRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final AgentService agents = mock(AgentService.class);
    private final McpStore mcps = mock(McpStore.class);
    private final CredentialStore credentialStore = mock(CredentialStore.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final GatewayTokenIssuer gatewayTokens = mock(GatewayTokenIssuer.class);
    private final PublicationStore publications = mock(PublicationStore.class);
    private final OpsIdentityClient identities = mock(OpsIdentityClient.class);
    private final PolicyService policy = mock(PolicyService.class);
    private final RunEventService events = mock(RunEventService.class);
    private final RuntimeEventIngestService ingest = mock(RuntimeEventIngestService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @Test
    void contextAuthorizeAndCredentialRejectCancellationPendingRun() {
        UUID runId = UUID.randomUUID();
        long token = 7L;
        Run run = run(runId, "CANCEL_REQUESTED", "runtime-1", token);
        when(runs.find(runId)).thenReturn(Optional.of(run));
        RuntimeRunCoordinator coordinator = coordinator();

        assertStale(() -> coordinator.context(runId, token));
        assertStale(() -> coordinator.authorize(
                new ToolAuthorizeRequest(runId, token, "mcp_ops__get_device", "digest", 1L, "request-1")));
        assertStale(() -> coordinator.resolveCredential(
                new CredentialResolveRequest(runId, token, "credential-key", 0, "RUN", null)));
    }

    @Test
    void cancellationFinalizationCanStillFlushEventsAndComplete() {
        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        long token = 7L;
        Run run = run(runId, "CANCEL_REQUESTED", "runtime-1", token, conversationId);
        when(runs.find(runId)).thenReturn(Optional.of(run));
        when(runs.lock(runId)).thenReturn(Optional.of(run));
        when(runs.markCancelled(runId, token, "runtime-1", Map.of())).thenReturn(true);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        RunEventAck ack = new RunEventAck(List.of(), 3L);
        RunEventBatch batch = new RunEventBatch(runId, token, "runtime-1", "request-1", List.of());
        when(ingest.ingest(conversationId, batch)).thenReturn(ack);

        RuntimeRunCoordinator coordinator = coordinator();
        coordinator.events(batch);
        coordinator.complete(new RunCompletionRequest(
                runId,
                token,
                "runtime-1",
                "request-2",
                RunTerminalStatus.SUCCEEDED,
                "",
                List.of(),
                List.of(),
                Map.of(),
                null,
                null));

        verify(ingest).ingest(conversationId, batch);
        verify(events).publish(eq(conversationId), eq(runId), eq("run.cancelled"), anyMap());
        verify(transactionManager).commit(status);
    }

    private RuntimeRunCoordinator coordinator() {
        return new RuntimeRunCoordinator(
                runs,
                conversations,
                agents,
                mcps,
                credentialStore,
                credentialService,
                gatewayTokens,
                publications,
                identities,
                policy,
                events,
                ingest,
                new PlatformProperties(null, null, new PlatformRuntime(3, 30, 100, 2, 1, null), null),
                new AiProperties("test", null, 0, null, null, new AiRun(4, 6, 12, 120_000, 20), null),
                transactionManager);
    }

    private static void assertStale(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(AiExceptions.AiException.class)
                .hasFieldOrPropertyWithValue("code", "STALE_EXECUTION_TOKEN");
    }

    private static Run run(UUID runId, String status, String instanceId, long token) {
        return run(runId, status, instanceId, token, UUID.randomUUID());
    }

    private static Run run(UUID runId, String status, String instanceId, long token, UUID conversationId) {
        Instant now = Instant.now();
        return new Run(
                runId,
                conversationId,
                UUID.randomUUID(),
                "user",
                List.of("USER"),
                1L,
                "问题",
                "agent",
                "1.0.0",
                "model",
                status,
                100,
                1L,
                "idempotency",
                "v1",
                "00-trace",
                instanceId,
                now.plusSeconds(60),
                Map.of(),
                Map.of(),
                null,
                null,
                now,
                null,
                now,
                now,
                UUID.randomUUID(),
                "digest",
                instanceId,
                token,
                now,
                1,
                now.plusSeconds(120),
                null,
                "CANCEL_REQUESTED".equals(status) ? now : null);
    }
}
