package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.McpRelease;
import top.yealike.pep.control.agents.domain.Publication;
import top.yealike.pep.control.agents.domain.RegistryProjection;
import top.yealike.pep.control.agents.domain.SkillRelease;
import top.yealike.pep.control.agents.mcp.McpService;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.RegistryStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.ComponentNames;
import top.yealike.pep.runtime.contract.ComponentType;

/** 发布记录重试与注册投影诊断。 */
@RestController
@RequestMapping("/api/ai/v1")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "AI 发布与注册", description = "发布状态、失败重试和组件注册投影")
public class PublicationAdminController {

    private final PublicationStore publications;
    private final RegistryStore registryStore;
    private final AgentService agents;
    private final McpService mcps;
    private final SkillService skills;

    public PublicationAdminController(
            PublicationStore publications,
            RegistryStore registryStore,
            AgentService agents,
            McpService mcps,
            SkillService skills) {
        this.publications = publications;
        this.registryStore = registryStore;
        this.agents = agents;
        this.mcps = mcps;
        this.skills = skills;
    }

    @GetMapping("/publications/{id}")
    @Operation(summary = "查询发布状态")
    public Publication publication(@Parameter(description = "发布记录 ID") @PathVariable UUID id) {
        return publications.find(id).orElseThrow(() -> AiExceptions.notFound("发布记录不存在"));
    }

    @PostMapping("/publications/{id}/retry")
    @Operation(summary = "重试失败的发布")
    public Publication retryPublication(@Parameter(description = "发布记录 ID") @PathVariable UUID id) {
        Publication publication = publications.find(id).orElseThrow(() -> AiExceptions.notFound("发布记录不存在"));
        publications.retry(id);
        return publications.find(id).orElseThrow(() -> AiExceptions.unavailable("发布记录暂时不可读"));
    }

    @GetMapping("/registry/components")
    public List<Map<String, Object>> registryComponents() {
        return registryStore.list().stream().map(this::registryView).toList();
    }

    @GetMapping("/registry/components/{type}/{key}/versions/{version}")
    public Map<String, Object> registryVersion(
            @PathVariable String type, @PathVariable String key, @PathVariable String version) {
        ComponentType componentType;
        try {
            componentType = ComponentType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("组件类型只支持 MCP、SKILL、AGENT");
        }
        String serviceName = ComponentNames.serviceName(componentType, key, version);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("componentType", componentType.name());
        data.put("componentKey", key);
        data.put("componentVersion", version);
        data.put("serviceName", serviceName);
        data.put("checkedAt", Instant.now());
        UUID releaseId = releaseId(componentType, key, version);
        if (releaseId != null) {
            data.put("releaseId", releaseId);
            registryStore.find(componentType.name(), releaseId).ifPresent(projection -> {
                data.put("desiredState", projection.desiredState());
                data.put("observedState", projection.observedState());
                data.putIfAbsent("lastError", projection.lastError());
            });
        }
        String state = switch (componentType) {
            case AGENT -> agents.release(key, version).state();
            case MCP -> mcps.release(key, version).state();
            case SKILL -> skills.release(key, version).state();
        };
        data.put("authority", "PLATFORM");
        data.put("instances", List.of());
        data.put("healthy", "ACTIVE".equals(state));
        data.put("releaseState", state);
        return data;
    }

    private Map<String, Object> registryView(RegistryProjection projection) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", projection.id());
        row.put("resourceType", projection.resourceType());
        row.put("releaseId", projection.releaseId());
        row.put("serviceName", projection.serviceName());
        row.put("providerId", projection.providerId());
        row.put("desiredState", projection.desiredState());
        row.put("observedState", projection.observedState());
        row.put("ownerNode", projection.ownerNode());
        row.put("lastSuccessAt", projection.lastSuccessAt());
        row.put("lastError", projection.lastError());
        row.put("healthy", "READY".equals(projection.observedState()));
        componentCoordinates(projection).forEach(row::putIfAbsent);
        return row;
    }

    private Map<String, Object> componentCoordinates(RegistryProjection projection) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            switch (projection.resourceType()) {
                case "MCP" -> {
                    McpRelease release = mcps.release(projection.releaseId());
                    result.put("componentKey", release.componentKey());
                    result.put("componentVersion", release.version());
                }
                case "SKILL" -> {
                    SkillRelease release = skills.release(projection.releaseId());
                    result.put("componentKey", release.componentKey());
                    result.put("componentVersion", release.version());
                }
                case "AGENT" -> {
                    AgentRelease release = agents.release(projection.releaseId());
                    result.put("componentKey", release.agentKey());
                    result.put("componentVersion", release.version());
                }
                default -> {
                    return result;
                }
            }
        } catch (RuntimeException ignored) {
            // 注册投影可能保留已删除或过期 release；列表仍应可用。
        }
        return result;
    }

    private UUID releaseId(ComponentType type, String key, String version) {
        try {
            return switch (type) {
                case MCP -> mcps.release(key, version).id();
                case SKILL -> skills.release(key, version).id();
                case AGENT -> agents.release(key, version).id();
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
