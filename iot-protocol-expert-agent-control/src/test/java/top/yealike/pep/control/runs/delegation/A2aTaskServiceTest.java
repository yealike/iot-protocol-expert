package top.yealike.pep.control.runs.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.audit.AiAuditLogMapper;
import top.yealike.pep.control.conversations.ConversationRepository;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.runs.RunEventService;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.control.runs.mapper.A2aTaskMapper;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.DelegateBinding;
import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.Digests;
import top.yealike.pep.runtime.contract.RunLineage;
import top.yealike.pep.runtime.contract.UserIdentity;

class A2aTaskServiceTest {

    private final UUID parentId = UUID.randomUUID();
    private final UUID sourceReleaseId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID childConversation = UUID.randomUUID();
    private final UUID targetReleaseId = UUID.randomUUID();
    private final A2aTaskMapper tasks = mock(A2aTaskMapper.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final DelegationAuthorizer authorizer = mock(DelegationAuthorizer.class);
    private final AgentStore agents = mock(AgentStore.class);
    private final OpsIdentityClient identities = mock(OpsIdentityClient.class);
    private final UserIdentity user = new UserIdentity("user", "user", List.of(), List.of(), 2);
    private final Instant now = Instant.now();
    private final A2aTaskService service = new A2aTaskService(
            tasks,
            runs,
            mock(ConversationRepository.class),
            agents,
            identities,
            mock(PolicyService.class),
            authorizer,
            new JdbcTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:a2a-task", "sa", "")),
            mock(RunEventService.class),
            mock(AiAuditLogMapper.class),
            new SimpleMeterRegistry(),
            2);

    @BeforeEach
    void stubParentExecution() {
        Run parent = mock(Run.class);
        when(parent.id()).thenReturn(parentId);
        when(parent.actor()).thenReturn(user.actor());
        when(parent.agentReleaseId()).thenReturn(sourceReleaseId);
        when(runs.find(parentId)).thenReturn(Optional.of(parent));
        when(runs.lock(parentId)).thenReturn(Optional.of(parent));
        when(identities.resolveActor(user.actor())).thenReturn(user);
        AgentRelease source = mock(AgentRelease.class);
        when(agents.findRelease(sourceReleaseId)).thenReturn(Optional.of(source));
        var binding = new DelegateBinding(
                "worker",
                "target",
                targetReleaseId,
                "1.0.0",
                "digest",
                "测试",
                null,
                List.of("lookup"),
                false,
                3,
                10_000,
                0);
        when(authorizer.binding(eq(source), eq("worker"), eq(user), eq(parent))).thenReturn(binding);
    }

    @Test
    void returnsPersistedResultWithoutCreatingAnotherChild() {
        A2aTask task = completedTask(Digests.digest(Map.of(
                "target", "worker", "task", "解释字段", "contextRefs", List.of(), "allowedTools", List.of("lookup"))));
        when(tasks.findByKey(parentId, "tool-call")).thenReturn(task);
        when(tasks.lock(taskId)).thenReturn(task);
        Run child = mock(Run.class);
        when(child.id()).thenReturn(childId);
        when(runs.find(childId)).thenReturn(Optional.of(child));
        when(runs.lineage(childId)).thenReturn(new RunLineage(parentId, parentId, taskId, 1, List.of("lookup"), 1000));

        var result = service.submit(request(List.of()));

        assertThat(result.created()).isFalse();
        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.resultSummary()).isEqualTo("字段说明");
        verify(tasks, never()).insertTask(any(), any());
    }

    @Test
    void rejectsReuseOfKeyForAnotherRequest() {
        when(tasks.findByKey(parentId, "tool-call")).thenReturn(completedTask("other-digest"));

        assertThatThrownBy(() -> service.submit(request(List.of())))
                .isInstanceOfSatisfying(
                        AiExceptions.AiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(tasks, never()).insertTask(any(), any());
    }

    @Test
    void rejectsContextReferencesBeforeAnyRunLookup() {
        assertThatThrownBy(() -> service.submit(request(List.of("message-reference"))))
                .isInstanceOfSatisfying(
                        AiExceptions.AiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CONTEXT_REFS_UNSUPPORTED"));
        verify(runs, never()).find(any());
    }

    private DelegateTaskRequest request(List<String> contextRefs) {
        return new DelegateTaskRequest(
                parentId, 1, "runtime", sourceReleaseId, "worker", "tool-call", "解释字段", contextRefs, null, null);
    }

    private A2aTask completedTask(String digest) {
        return new A2aTask(
                taskId,
                parentId,
                parentId,
                childId,
                childConversation,
                parentId.toString(),
                UUID.randomUUID(),
                sourceReleaseId,
                UUID.randomUUID(),
                targetReleaseId,
                "worker",
                1,
                A2aTaskState.COMPLETED,
                "tool-call",
                digest,
                Map.of(),
                Map.of("summary", "字段说明", "truncated", false),
                null,
                null,
                null,
                now,
                2,
                now,
                now);
    }
}
