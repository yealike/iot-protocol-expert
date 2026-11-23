package top.yealike.pep.control.api.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.api.internal.mapper.InternalControlMapper;
import top.yealike.pep.control.runs.runtime.RuntimeRunCoordinator;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.ControlReadiness;
import top.yealike.pep.runtime.contract.CredentialResolveRequest;
import top.yealike.pep.runtime.contract.CredentialResolveResponse;
import top.yealike.pep.runtime.contract.DesiredRelease;
import top.yealike.pep.runtime.contract.DesiredReleasePage;
import top.yealike.pep.runtime.contract.InternalPaths;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.OperationResult;
import top.yealike.pep.runtime.contract.RunClaimRequest;
import top.yealike.pep.runtime.contract.RunClaimResponse;
import top.yealike.pep.runtime.contract.RunCompletionRequest;
import top.yealike.pep.runtime.contract.RunContext;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;
import top.yealike.pep.runtime.contract.SkillDescriptor;
import top.yealike.pep.runtime.contract.SkillFile;
import top.yealike.pep.runtime.contract.ToolAuthorizeRequest;
import top.yealike.pep.runtime.contract.ToolAuthorizeResponse;

/** Runtime 依赖的 Control 内部接口；鉴权由 InternalSignatureFilter 完成。 */
@RestController
@RequestMapping(InternalPaths.CONTROL_BASE)
public class InternalControlController {

    private final AgentService agents;
    private final AgentStore agentStore;
    private final McpService mcps;
    private final SkillService skills;
    private final RuntimeRunCoordinator runs;
    private final InternalControlMapper control;

    public InternalControlController(
            AgentService agents,
            AgentStore agentStore,
            McpService mcps,
            SkillService skills,
            RuntimeRunCoordinator runs,
            InternalControlMapper control) {
        this.agents = agents;
        this.agentStore = agentStore;
        this.mcps = mcps;
        this.skills = skills;
        this.runs = runs;
        this.control = control;
    }

    @GetMapping("/releases/{releaseId}/manifest")
    public AgentManifest manifest(@PathVariable UUID releaseId) {
        AgentRelease release = agents.release(releaseId);
        if ("FAILED".equals(release.state()) || "REVOKED".equals(release.state())) {
            throw AiExceptions.conflict("RELEASE_NOT_ACTIVE", "发布版本不可用于执行");
        }
        return release.manifest();
    }

    @GetMapping("/mcps/{key}/versions/{version}/descriptor")
    public McpDescriptor mcpDescriptor(@PathVariable String key, @PathVariable String version) {
        return mcps.descriptor(key, version);
    }

    @GetMapping("/skills/{key}/versions/{version}")
    public SkillDescriptor skillDescriptor(@PathVariable String key, @PathVariable String version) {
        return skills.descriptor(key, version);
    }

    @GetMapping("/skills/{key}/versions/{version}/file")
    public SkillFile skillFile(@PathVariable String key, @PathVariable String version, @RequestParam String path) {
        return skills.releaseFile(key, version, path);
    }

    @PostMapping("/runs/{runId}:claim")
    public RunClaimResponse claim(@PathVariable UUID runId, @RequestBody RunClaimRequest request) {
        requireSameRun(runId, request == null ? null : request.runId());
        return runs.claim(request);
    }

    @PostMapping("/runs/{runId}:heartbeat")
    public RunClaimResponse heartbeat(@PathVariable UUID runId, @RequestBody RunClaimRequest request) {
        requireSameRun(runId, request == null ? null : request.runId());
        return runs.heartbeat(request);
    }

    @GetMapping("/runs/{runId}/context")
    public RunContext context(@PathVariable UUID runId, @RequestParam long executionToken) {
        return runs.context(runId, executionToken);
    }

    @PostMapping("/runs/{runId}/events:batch")
    public RunEventAck events(@PathVariable UUID runId, @RequestBody RunEventBatch batch) {
        requireSameRun(runId, batch == null ? null : batch.runId());
        return runs.events(batch);
    }

    @PostMapping("/runs/{runId}:complete")
    public OperationResult complete(@PathVariable UUID runId, @RequestBody RunCompletionRequest request) {
        requireSameRun(runId, request == null ? null : request.runId());
        runs.complete(request);
        return new OperationResult(true, "COMPLETED");
    }

    @PostMapping("/credentials:resolve")
    public CredentialResolveResponse resolveCredential(@RequestBody CredentialResolveRequest request) {
        return runs.resolveCredential(request);
    }

    @PostMapping("/runs/{runId}/tools:authorize")
    public ToolAuthorizeResponse authorize(@PathVariable UUID runId, @RequestBody ToolAuthorizeRequest request) {
        requireSameRun(runId, request == null ? null : request.runId());
        return runs.authorize(request);
    }

    @GetMapping("/runtime/desired-releases")
    public DesiredReleasePage desiredReleases(
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "100") int limit) {
        int normalized = Math.min(Math.max(limit, 1), 200);
        long lastSeen = cursor == null || cursor < 0 ? 0L : cursor;
        List<AgentRelease> candidates = agentStore.desiredReleases().stream()
                .filter(release -> release.releaseCursor() > lastSeen)
                .toList();
        List<DesiredRelease> items = new ArrayList<>();
        long nextCursor = lastSeen;
        for (AgentRelease release : candidates) {
            if (items.size() >= normalized) {
                break;
            }
            items.add(new DesiredRelease(
                    release.id(),
                    release.agentKey(),
                    release.version(),
                    release.manifestDigest(),
                    release.generation(),
                    release.state()));
            nextCursor = release.releaseCursor();
        }
        return new DesiredReleasePage(items, nextCursor, normalized, candidates.size() > items.size());
    }

    @GetMapping("/runtime/readiness")
    public ControlReadiness readiness() {
        Integer probe = control.probe();
        boolean ready = probe != null && probe == 1;
        return new ControlReadiness("UP", ready, "ipe-ai-control", ready);
    }

    private static void requireSameRun(UUID pathRunId, UUID bodyRunId) {
        if (bodyRunId == null || !pathRunId.equals(bodyRunId)) {
            throw AiExceptions.badRequest("路径 runId 与请求体不一致");
        }
    }
}
