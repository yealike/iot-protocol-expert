package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import top.yealike.pep.control.agents.domain.RegistryComponentView;
import top.yealike.pep.control.agents.domain.RegistryProjection;
import top.yealike.pep.control.agents.domain.RegistryVersionView;
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
    public List<RegistryComponentView> registryComponents() {
        return registryStore.list().stream().map(this::registryView).toList();
    }

    @GetMapping("/registry/components/{type}/{key}/versions/{version}")
    public RegistryVersionView registryVersion(
            @PathVariable String type, @PathVariable String key, @PathVariable String version) {
        ComponentType componentType;
        try {
            componentType = ComponentType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("组件类型只支持 MCP、SKILL、AGENT");
        }
        String serviceName = ComponentNames.serviceName(componentType, key, version);
        UUID releaseId = releaseId(componentType, key, version);
        String desiredState = null;
        String observedState = null;
        String lastError = null;
        if (releaseId != null) {
            RegistryProjection projection =
                    registryStore.find(componentType.name(), releaseId).orElse(null);
            if (projection != null) {
                desiredState = projection.desiredState();
                observedState = projection.observedState();
                lastError = projection.lastError();
            }
        }
        String state = switch (componentType) {
            case AGENT -> agents.release(key, version).state();
            case MCP -> mcps.release(key, version).state();
            case SKILL -> skills.release(key, version).state();
        };
        return new RegistryVersionView(
                componentType.name(),
                key,
                version,
                serviceName,
                Instant.now(),
                releaseId,
                desiredState,
                observedState,
                lastError,
                "PLATFORM",
                List.of(),
                "ACTIVE".equals(state),
                state);
    }

    private RegistryComponentView registryView(RegistryProjection projection) {
        ComponentCoordinates coordinates = componentCoordinates(projection);
        return new RegistryComponentView(
                projection.id(),
                projection.resourceType(),
                projection.releaseId(),
                projection.serviceName(),
                projection.providerId(),
                projection.desiredState(),
                projection.observedState(),
                projection.ownerNode(),
                projection.lastSuccessAt(),
                projection.lastError(),
                "READY".equals(projection.observedState()),
                coordinates.key(),
                coordinates.version());
    }

    private ComponentCoordinates componentCoordinates(RegistryProjection projection) {
        try {
            return switch (projection.resourceType()) {
                case "MCP" -> {
                    McpRelease release = mcps.release(projection.releaseId());
                    yield new ComponentCoordinates(release.componentKey(), release.version());
                }
                case "SKILL" -> {
                    SkillRelease release = skills.release(projection.releaseId());
                    yield new ComponentCoordinates(release.componentKey(), release.version());
                }
                case "AGENT" -> {
                    AgentRelease release = agents.release(projection.releaseId());
                    yield new ComponentCoordinates(release.agentKey(), release.version());
                }
                default -> new ComponentCoordinates(null, null);
            };
        } catch (RuntimeException ignored) {
            // 注册投影可能保留已删除或过期 release；列表仍应可用。
            return new ComponentCoordinates(null, null);
        }
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

    private record ComponentCoordinates(String key, String version) {}
}
