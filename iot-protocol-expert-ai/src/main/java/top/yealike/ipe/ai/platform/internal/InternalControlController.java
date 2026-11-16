package top.yealike.ipe.ai.platform.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.contract.CredentialResolveRequest;
import top.yealike.ipe.ai.contract.CredentialResolveResponse;
import top.yealike.ipe.ai.contract.InternalPaths;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.contract.RunClaimRequest;
import top.yealike.ipe.ai.contract.RunClaimResponse;
import top.yealike.ipe.ai.contract.RunCompletionRequest;
import top.yealike.ipe.ai.contract.RunContext;
import top.yealike.ipe.ai.contract.RunEventAck;
import top.yealike.ipe.ai.contract.RunEventBatch;
import top.yealike.ipe.ai.contract.SkillDescriptor;
import top.yealike.ipe.ai.contract.SkillFile;
import top.yealike.ipe.ai.contract.ToolAuthorizeRequest;
import top.yealike.ipe.ai.contract.ToolAuthorizeResponse;
import top.yealike.ipe.ai.platform.agent.AgentService;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentRelease;
import top.yealike.ipe.ai.platform.mcp.McpService;
import top.yealike.ipe.ai.platform.skill.SkillService;
import top.yealike.ipe.ai.platform.store.AgentStore;
import top.yealike.ipe.ai.support.AiExceptions;

/** Runtime 依赖的 Control 内部接口；鉴权由 InternalSignatureFilter 完成。 */
@RestController
@RequestMapping(InternalPaths.CONTROL_BASE)
public class InternalControlController {

    private final AgentService agents;
    private final AgentStore agentStore;
    private final McpService mcps;
    private final SkillService skills;
    private final RuntimeRunCoordinator runs;
    private final JdbcTemplate jdbc;

    public InternalControlController(
            AgentService agents,
            AgentStore agentStore,
            McpService mcps,
            SkillService skills,
            RuntimeRunCoordinator runs,
            JdbcTemplate jdbc) {
        this.agents = agents;
        this.agentStore = agentStore;
        this.mcps = mcps;
        this.skills = skills;
        this.runs = runs;
        this.jdbc = jdbc;
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
    public Map<String, Object> complete(@PathVariable UUID runId, @RequestBody RunCompletionRequest request) {
        requireSameRun(runId, request == null ? null : request.runId());
        runs.complete(request);
        return Map.of("ok", true);
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
    public Map<String, Object> desiredReleases(
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "100") int limit) {
        int normalized = Math.min(Math.max(limit, 1), 200);
        long lastSeen = cursor == null || cursor < 0 ? 0L : cursor;
        List<AgentRelease> candidates = agentStore.desiredReleases().stream()
                .filter(release -> release.releaseCursor() > lastSeen)
                .toList();
        List<Map<String, Object>> items = new ArrayList<>();
        long nextCursor = lastSeen;
        for (AgentRelease release : candidates) {
            if (items.size() >= normalized) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("releaseId", release.id());
            item.put("agentKey", release.agentKey());
            item.put("version", release.version());
            item.put("manifestDigest", release.manifestDigest());
            item.put("generation", release.generation());
            item.put("state", release.state());
            items.add(item);
            nextCursor = release.releaseCursor();
        }
        return Map.of(
                "items", items, "cursor", nextCursor, "limit", normalized, "hasMore", candidates.size() > items.size());
    }

    @GetMapping("/runtime/readiness")
    public Map<String, Object> readiness() {
        Integer probe = jdbc.queryForObject("select 1", Integer.class);
        return Map.of(
                "status",
                "UP",
                "readiness",
                probe != null && probe == 1,
                "service",
                "ipe-ai-control",
                "database",
                probe != null && probe == 1);
    }

    private static void requireSameRun(UUID pathRunId, UUID bodyRunId) {
        if (bodyRunId == null || !pathRunId.equals(bodyRunId)) {
            throw AiExceptions.badRequest("路径 runId 与请求体不一致");
        }
    }
}
