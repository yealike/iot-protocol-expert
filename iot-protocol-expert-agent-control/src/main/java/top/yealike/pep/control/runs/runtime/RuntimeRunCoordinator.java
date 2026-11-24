package top.yealike.pep.control.runs.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.control.access.policy.PolicyService;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.Credential;
import top.yealike.pep.control.agents.domain.McpRelease;
import top.yealike.pep.control.agents.domain.Publication;
import top.yealike.pep.control.agents.model.GatewayTokenIssuer;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.config.properties.AiProperties;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.conversations.ConversationRepository;
import top.yealike.pep.control.conversations.domain.Message;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.runs.RunEventService;
import top.yealike.pep.control.runs.RunRepository;
import top.yealike.pep.control.runs.RunStatus;
import top.yealike.pep.control.runs.domain.Run;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.model.contract.ModelGatewayCredentials;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.CredentialResolveRequest;
import top.yealike.pep.runtime.contract.CredentialResolveResponse;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.RunClaimRequest;
import top.yealike.pep.runtime.contract.RunClaimResponse;
import top.yealike.pep.runtime.contract.RunCompletionRequest;
import top.yealike.pep.runtime.contract.RunContext;
import top.yealike.pep.runtime.contract.RunEventBatch;
import top.yealike.pep.runtime.contract.RunMessage;
import top.yealike.pep.runtime.contract.RunTerminalStatus;
import top.yealike.pep.runtime.contract.ToolAuthorizeRequest;
import top.yealike.pep.runtime.contract.ToolAuthorizeResponse;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 内部执行协调：claim/heartbeat/context/events/complete/authorize/credential。 */
@Service
public class RuntimeRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRunCoordinator.class);
    private static final int MAX_ANSWER_CHARS = 200_000;

    private final RunRepository runs;
    private final ConversationRepository conversations;
    private final AgentService agents;
    private final McpStore mcps;
    private final CredentialStore credentialStore;
    private final CredentialService credentialService;
    private final GatewayTokenIssuer gatewayTokens;
    private final PublicationStore publications;
    private final OpsIdentityClient identities;
    private final PolicyService policy;
    private final RunEventService events;
    private final RuntimeEventIngestService ingest;
    private final PlatformProperties platform;
    private final AiProperties properties;
    private final TransactionTemplate transactions;

    public RuntimeRunCoordinator(
            RunRepository runs,
            ConversationRepository conversations,
            AgentService agents,
            McpStore mcps,
            CredentialStore credentialStore,
            CredentialService credentialService,
            GatewayTokenIssuer gatewayTokens,
            PublicationStore publications,
            OpsIdentityClient identities,
            PolicyService policy,
            RunEventService events,
            RuntimeEventIngestService ingest,
            PlatformProperties platform,
            AiProperties properties,
            PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.conversations = conversations;
        this.agents = agents;
        this.mcps = mcps;
        this.credentialStore = credentialStore;
        this.credentialService = credentialService;
        this.gatewayTokens = gatewayTokens;
        this.publications = publications;
        this.identities = identities;
        this.policy = policy;
        this.events = events;
        this.ingest = ingest;
        this.platform = platform;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RunClaimResponse claim(RunClaimRequest request) {
        Run run = runs.find(request.runId()).orElseThrow(() -> AiExceptions.notFound("Run 不存在"));
        boolean directOwner = RunStatus.DISPATCHING.equals(run.status())
                && (run.runtimeInstanceId() == null || "direct".equals(run.runtimeInstanceId()));
        boolean tokenValid = run.executionToken() == request.executionToken()
                && (Objects.equals(run.runtimeInstanceId(), request.instanceId()) || directOwner);
        if (!tokenValid
                || (!RunStatus.DISPATCHING.equals(run.status())
                        && !RunStatus.RUNNING.equals(run.status())
                        && !RunStatus.CANCEL_REQUESTED.equals(run.status()))) {
            return new RunClaimResponse(
                    false,
                    ErrorCodes.STALE_EXECUTION_TOKEN,
                    run.runtimeInstanceId(),
                    run.deadlineAt(),
                    false,
                    run.conversationId(),
                    run.actor(),
                    run.actorAuthVersion() == null ? 0 : run.actorAuthVersion(),
                    run.actorRoles(),
                    null);
        }
        if (run.deadlineAt() != null && run.deadlineAt().isBefore(Instant.now())) {
            return new RunClaimResponse(
                    false,
                    "DEADLINE_EXCEEDED",
                    run.runtimeInstanceId(),
                    run.deadlineAt(),
                    true,
                    run.conversationId(),
                    run.actor(),
                    run.actorAuthVersion() == null ? 0 : run.actorAuthVersion(),
                    run.actorRoles(),
                    null);
        }
        if (run.agentReleaseId() == null) {
            return deniedClaim(run, "AGENT_RELEASE_MISSING", false);
        }
        AgentRelease release = agents.release(run.agentReleaseId());
        if (RunStatus.DISPATCHING.equals(run.status())
                && !release.manifest().delegates().isEmpty()) {
            boolean unavailable = release.manifest().delegates().stream()
                    .filter(top.yealike.pep.runtime.contract.DelegateBinding::required)
                    .anyMatch(binding -> !agents.delegateTargetActive(binding));
            if (unavailable) {
                return deniedClaim(run, "TARGET_RELEASE_UNAVAILABLE", false);
            }
        }
        var runLineage = runs.lineage(run.id());
        if (runLineage != null && !isParentRunning(runLineage.parentRunId(), request.instanceId())) {
            runs.requestCancel(run.id());
            return deniedClaim(run, "PARENT_RUN_STOPPED", true);
        }
        if (RunStatus.DISPATCHING.equals(run.status())) {
            try {
                UserIdentity current = identities.resolveActor(run.actor());
                long stored = run.actorAuthVersion() == null ? 0 : run.actorAuthVersion();
                if (current.authVersion() != stored) {
                    return deniedClaim(run, "AUTH_VERSION_CHANGED", true);
                }
            } catch (AiExceptions.AiException exception) {
                return deniedClaim(run, "IDENTITY_UNAVAILABLE", false);
            }
        }
        boolean cancelRequired = RunStatus.CANCEL_REQUESTED.equals(run.status()) || "REVOKED".equals(release.state());
        if (!runs.heartbeat(run.id(), run.executionToken(), request.instanceId(), leaseSeconds())) {
            return deniedClaim(run, ErrorCodes.STALE_EXECUTION_TOKEN, false);
        }
        UserIdentity identity = identity(run);
        return new RunClaimResponse(
                true,
                "OK",
                run.runtimeInstanceId(),
                run.deadlineAt(),
                cancelRequired,
                run.conversationId(),
                run.actor(),
                identity.authVersion(),
                run.actorRoles(),
                release.manifest().budget());
    }

    private RunClaimResponse deniedClaim(Run run, String reason, boolean cancelRequired) {
        return new RunClaimResponse(
                false,
                reason,
                run.runtimeInstanceId(),
                run.deadlineAt(),
                cancelRequired,
                run.conversationId(),
                run.actor(),
                run.actorAuthVersion() == null ? 0 : run.actorAuthVersion(),
                run.actorRoles(),
                null);
    }

    public RunClaimResponse heartbeat(RunClaimRequest request) {
        return claim(request);
    }

    public RunContext context(UUID runId, long executionToken) {
        Run run = requireActiveExecution(runId, executionToken, false);
        List<Message> history = conversations.listMessagesThrough(
                run.conversationId(),
                run.userMessageId(),
                Math.max(4, properties.run().maxContextMessages()));
        List<RunMessage> messages = new ArrayList<>();
        for (Message message : history) {
            if (message.id().equals(run.userMessageId())) {
                continue;
            }
            messages.add(new RunMessage(message.role(), message.content(), null, null));
        }
        return new RunContext(run.id(), run.conversationId(), run.question(), messages, runs.lineage(run.id()));
    }

    public top.yealike.pep.runtime.contract.RunEventAck events(RunEventBatch batch) {
        Run run = requireActiveExecution(batch.runId(), batch.executionToken(), true);
        if (!Objects.equals(run.runtimeInstanceId(), batch.instanceId())) {
            throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "执行实例已失去该 Run 的所有权");
        }
        return ingest.ingest(run.conversationId(), batch);
    }

    public void complete(RunCompletionRequest request) {
        Run run = requireToken(request.runId(), request.executionToken());
        if (!Objects.equals(run.runtimeInstanceId(), request.instanceId())) {
            throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "执行实例已失去该 Run 的所有权");
        }
        if (run.terminal()) {
            return;
        }
        RunTerminalStatus requestedStatus = request.status();
        if (requestedStatus == null) {
            throw AiExceptions.badRequest("终态不能为空");
        }
        String answer = request.answer() == null ? "" : request.answer();
        if (answer.length() > MAX_ANSWER_CHARS) {
            throw AiExceptions.badRequest("最终回答超过允许长度");
        }
        transactions.executeWithoutResult(ignored -> {
            Run current = runs.lock(request.runId()).orElseThrow(() -> AiExceptions.notFound("Run 不存在"));
            if (current.terminal()) {
                return;
            }
            if (current.executionToken() != request.executionToken()
                    || !Objects.equals(current.runtimeInstanceId(), request.instanceId())) {
                throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "终态提交已过期或 Run 已终结");
            }
            if (current.leaseUntil() == null
                    || !current.leaseUntil().isAfter(Instant.now())
                    || (current.deadlineAt() != null && !current.deadlineAt().isAfter(Instant.now()))) {
                throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "Run 执行权已失效或已超时");
            }
            RunTerminalStatus status =
                    RunStatus.CANCEL_REQUESTED.equals(current.status()) ? RunTerminalStatus.CANCELLED : requestedStatus;
            var usage = request.usage();
            if (runs.lineage(current.id()) == null && usage != null) {
                var aggregate = runs.withDescendantUsage(current.id(), usage);
                if (aggregate != null) {
                    usage = aggregate;
                }
            }
            boolean changed;
            String type;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", status.name());
            payload.put("usage", usage);
            payload.put("warnings", request.warnings());
            switch (status) {
                case SUCCEEDED -> {
                    changed = runs.markCompleted(current.id(), current.executionToken(), request.instanceId(), usage);
                    if (changed && !answer.isBlank()) {
                        long seq = conversations.nextMessageSeq(current.conversationId());
                        UUID messageId = UUID.randomUUID();
                        conversations.insertMessage(
                                messageId,
                                current.conversationId(),
                                current.id(),
                                "assistant",
                                answer,
                                request.citations(),
                                "FINAL",
                                seq,
                                Instant.now());
                        payload.put("messageId", messageId.toString());
                        payload.put("citations", request.citations());
                    }
                    type = "run.completed";
                }
                case CANCELLED -> {
                    changed = runs.markCancelled(current.id(), current.executionToken(), request.instanceId(), usage);
                    type = "run.cancelled";
                }
                default -> {
                    String code =
                            request.errorCode() == null || request.errorCode().isBlank()
                                    ? (status == RunTerminalStatus.TIMED_OUT ? "RUN_TIMED_OUT" : "RUN_FAILED")
                                    : request.errorCode();
                    String message = request.errorMessage() == null
                                    || request.errorMessage().isBlank()
                            ? "执行失败"
                            : request.errorMessage();
                    payload.put("errorCode", code);
                    payload.put("message", message);
                    changed = runs.markFailed(
                            current.id(), current.executionToken(), request.instanceId(), code, message, usage);
                    type = "run.failed";
                }
            }
            if (!changed) {
                throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "终态提交已过期或 Run 已终结");
            }
            events.publish(current.conversationId(), current.id(), type, payload);
        });
    }

    public ToolAuthorizeResponse authorize(ToolAuthorizeRequest request) {
        Run run = requireActiveExecution(request.runId(), request.executionToken(), false);
        var lineage = runs.lineage(run.id());
        if (lineage != null
                && !lineage.allowedTools().isEmpty()
                && !lineage.allowedTools().contains(request.toolAlias())) {
            return denied(run, ErrorCodes.TOOL_DENIED, "委派绑定未开放该工具");
        }
        AgentRelease release = agents.release(run.agentReleaseId());
        AgentManifest manifest = release.manifest();
        AgentManifest.McpBinding binding = manifest.mcp().stream()
                .filter(candidate ->
                        candidate.tools().stream().anyMatch(tool -> tool.alias().equals(request.toolAlias())))
                .findFirst()
                .orElse(null);
        if (binding == null) {
            return denied(run, ErrorCodes.TOOL_DENIED, "工具不在当前 Agent 发布清单内");
        }
        if ("REVOKED".equals(release.state())) {
            return denied(run, ErrorCodes.TOOL_DENIED, "Agent 版本已紧急停用");
        }
        McpRelease mcpRelease =
                mcps.findRelease(binding.key(), binding.version()).orElse(null);
        if (mcpRelease == null || "REVOKED".equals(mcpRelease.state())) {
            return denied(run, ErrorCodes.TOOL_DENIED, "MCP 版本已停用");
        }
        AgentManifest.McpToolBinding boundTool = binding.tools().stream()
                .filter(tool -> tool.alias().equals(request.toolAlias()))
                .findFirst()
                .orElse(null);
        if (boundTool == null
                || !mcps.toolCategories(mcpRelease.componentId())
                        .getOrDefault(
                                boundTool.name(), top.yealike.pep.runtime.contract.McpToolExecutionCategory.UNKNOWN)
                        .readOnly()) {
            return denied(run, ErrorCodes.TOOL_DENIED, "一期仅允许调用平台审核通过的只读工具");
        }
        long storedVersion = run.actorAuthVersion() == null ? 0 : run.actorAuthVersion();
        UserIdentity identity;
        try {
            identity = identities.resolveActor(run.actor());
        } catch (AiExceptions.AiException exception) {
            return denied(run, "IDENTITY_UNAVAILABLE", "身份校验服务不可用");
        }
        long currentVersion = identity.authVersion();
        if (request.authVersion() != storedVersion || currentVersion != storedVersion) {
            return denied(run, "AUTH_VERSION_CHANGED", "用户授权版本已变化，请重新登录");
        }
        if (!policy.canUseTool(identity, run.agentKey(), request.toolAlias()).allowed()) {
            return denied(run, ErrorCodes.TOOL_DENIED, "当前账号无权调用该工具");
        }
        return new ToolAuthorizeResponse(true, "OK", null, run.actor(), currentVersion);
    }

    public CredentialResolveResponse resolveCredential(CredentialResolveRequest request) {
        Set<String> allowedRefs = new java.util.HashSet<>();
        Run resolvedRun = null;
        if (request.runId() != null) {
            requireActiveExecution(request.runId(), request.executionToken(), false);
            resolvedRun = runs.find(request.runId()).orElseThrow(() -> AiExceptions.notFound("Run 不存在"));
            AgentRelease release = agents.release(resolvedRun.agentReleaseId());
            var lineage = runs.lineage(resolvedRun.id());
            collectManifestCredentials(
                    release.manifest(), allowedRefs, lineage == null ? List.of() : lineage.allowedTools());
        } else if (request.publicationId() != null) {
            Publication publication = publications.find(request.publicationId()).orElse(null);
            if (publication != null) {
                collectPublicationCredentials(publication, allowedRefs);
            } else {
                // Runtime 重启对账时只有 releaseId；prepare 请求把 releaseId 放在 publicationId 字段。
                AgentRelease release = agents.release(request.publicationId());
                collectManifestCredentials(release.manifest(), allowedRefs);
            }
        }
        if (request.credentialRef() == null || !allowedRefs.contains(request.credentialRef())) {
            throw AiExceptions.forbidden(ErrorCodes.CREDENTIAL_UNAVAILABLE, "运行清单未引用该凭据");
        }
        if (ModelGatewayCredentials.REF.equals(request.credentialRef())) {
            if (resolvedRun == null) {
                throw AiExceptions.forbidden(ErrorCodes.CREDENTIAL_UNAVAILABLE, "只有运行中的 Run 可以获取模型网关凭据");
            }
            // 上游供应商密钥只在模型网关；Runtime 仅拿到受众限定的短期运行凭据。
            var lineage = runs.lineage(resolvedRun.id());
            String token = lineage == null
                    ? gatewayTokens.issueForRun(
                            resolvedRun.actor(), resolvedRun.conversationId(), resolvedRun.id(), resolvedRun.modelKey())
                    : gatewayTokens.issueForChild(resolvedRun, lineage);
            return new CredentialResolveResponse(
                    request.credentialRef(), 1L, token, Instant.now().plusSeconds(600));
        }
        String secret = credentialService.resolve(request.credentialRef(), request.secretRevision());
        return new CredentialResolveResponse(
                request.credentialRef(),
                request.secretRevision(),
                secret,
                Instant.now().plusSeconds(300));
    }

    private void collectManifestCredentials(AgentManifest manifest, Set<String> refs) {
        collectManifestCredentials(manifest, refs, List.of());
    }

    private void collectManifestCredentials(AgentManifest manifest, Set<String> refs, List<String> allowedTools) {
        if (manifest.model() != null && manifest.model().credentialRef() != null) {
            refs.add(manifest.model().credentialRef());
        }
        for (AgentManifest.McpBinding binding : manifest.mcp()) {
            if (allowedTools.isEmpty()
                    || binding.tools().stream().anyMatch(tool -> allowedTools.contains(tool.alias()))) {
                mcps.findRelease(binding.key(), binding.version())
                        .ifPresent(release -> addMcpCredential(release, refs));
            }
        }
    }

    private void collectPublicationCredentials(Publication publication, Set<String> refs) {
        switch (publication.resourceType()) {
            case "AGENT" ->
                agents.releases(publication.resourceId()).stream()
                        .filter(item -> item.version().equals(publication.version()))
                        .findFirst()
                        .ifPresent(release -> collectManifestCredentials(release.manifest(), refs));
            case "MCP" ->
                mcps.releases(publication.resourceId()).stream()
                        .filter(item -> item.version().equals(publication.version()))
                        .findFirst()
                        .ifPresent(release -> addMcpCredential(release, refs));
            default -> {
                // Skill 不引用密钥
            }
        }
    }

    private void addMcpCredential(McpRelease release, Set<String> refs) {
        if (release.credentialId() == null) {
            return;
        }
        credentialStore.find(release.credentialId()).map(Credential::key).ifPresent(refs::add);
    }

    private Run requireToken(UUID runId, long executionToken) {
        Run run = runs.find(runId).orElseThrow(() -> AiExceptions.notFound("Run 不存在"));
        if (run.executionToken() != executionToken) {
            throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "执行令牌已过期");
        }
        return run;
    }

    private Run requireActiveExecution(UUID runId, long executionToken, boolean allowCancellationPending) {
        Run run = requireToken(runId, executionToken);
        if (run.runtimeInstanceId() != null
                && !"direct".equals(run.runtimeInstanceId())
                && !Objects.equals(run.runtimeInstanceId(), run.leaseOwner())) {
            throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "执行实例已失去该 Run 的所有权");
        }
        if (run.deadlineAt() != null && !run.deadlineAt().isAfter(Instant.now())) {
            throw AiExceptions.conflict("RUN_TIMEOUT", "Run 已超过执行截止时间");
        }
        boolean active = RunStatus.DISPATCHING.equals(run.status())
                || RunStatus.RUNNING.equals(run.status())
                || (allowCancellationPending && RunStatus.CANCEL_REQUESTED.equals(run.status()));
        if (!active || run.leaseUntil() == null || !run.leaseUntil().isAfter(Instant.now())) {
            throw AiExceptions.conflict(ErrorCodes.STALE_EXECUTION_TOKEN, "Run 执行权已失效或已终结");
        }
        return run;
    }

    private ToolAuthorizeResponse denied(Run run, String code, String reason) {
        return new ToolAuthorizeResponse(
                false, code, reason, run.actor(), run.actorAuthVersion() == null ? 0 : run.actorAuthVersion());
    }

    private UserIdentity identity(Run run) {
        return new UserIdentity(
                run.actor(),
                run.actor(),
                run.actorRoles() == null ? List.of() : run.actorRoles(),
                List.of(),
                run.actorAuthVersion() == null ? 0 : run.actorAuthVersion());
    }

    private int leaseSeconds() {
        return Math.max(30, platform.runtime().leaseSeconds());
    }

    private boolean isParentRunning(UUID parentRunId, String instanceId) {
        Instant now = Instant.now();
        return runs.find(parentRunId)
                .filter(parent -> RunStatus.RUNNING.equals(parent.status())
                        && Objects.equals(parent.runtimeInstanceId(), instanceId)
                        && parent.leaseUntil() != null
                        && parent.leaseUntil().isAfter(now)
                        && parent.deadlineAt() != null
                        && parent.deadlineAt().isAfter(now))
                .isPresent();
    }
}
