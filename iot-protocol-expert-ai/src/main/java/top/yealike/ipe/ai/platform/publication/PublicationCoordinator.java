package top.yealike.ipe.ai.platform.publication;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.contract.ComponentNames;
import top.yealike.ipe.ai.contract.ComponentType;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.ReleasePrepareRequest;
import top.yealike.ipe.ai.platform.dispatch.RuntimeDispatchClient;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.AgentRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.Publication;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillRelease;
import top.yealike.ipe.ai.platform.registry.NacosRegistryService;
import top.yealike.ipe.ai.platform.registry.RegistryProjectionService;
import top.yealike.ipe.ai.platform.store.AgentStore;
import top.yealike.ipe.ai.platform.store.McpStore;
import top.yealike.ipe.ai.platform.store.PublicationStore;
import top.yealike.ipe.ai.platform.store.SkillStore;
import top.yealike.ipe.ai.support.AiExceptions;

/** 发布协调：outbox 触发 + publication 轮询，按阶段幂等推进，失败指数退避。 */
@Service
public class PublicationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PublicationCoordinator.class);
    private static final int MAX_ATTEMPTS = 5;

    private final PublicationStore publications;
    private final McpStore mcps;
    private final SkillStore skills;
    private final AgentStore agents;
    private final RuntimeDispatchClient runtime;
    private final NacosRegistryService registry;
    private final RegistryProjectionService projections;
    private final String worker;

    public PublicationCoordinator(
            PublicationStore publications,
            McpStore mcps,
            SkillStore skills,
            AgentStore agents,
            RuntimeDispatchClient runtime,
            NacosRegistryService registry,
            RegistryProjectionService projections) {
        this.publications = publications;
        this.mcps = mcps;
        this.skills = skills;
        this.agents = agents;
        this.runtime = runtime;
        this.registry = registry;
        this.projections = projections;
        this.worker = "publication-" + ManagementFactory.getRuntimeMXBean().getName() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    @PostConstruct
    void start() {
        log.info("publication coordinator started: worker={}", worker);
    }

    @Scheduled(fixedDelay = 1_000L, initialDelay = 3_000L)
    public void tick() {
        try {
            processOutbox();
            for (Publication publication : publications.claimPending(worker, 30, 20)) {
                try {
                    process(publication);
                } catch (RuntimeException exception) {
                    log.warn(
                            "publication step failed: id={} type={} reason={}",
                            publication.id(),
                            publication.resourceType(),
                            safeMessage(exception));
                    retry(publication, "PUBLICATION_STEP_FAILED", "发布步骤失败，稍后自动重试");
                }
            }
        } catch (RuntimeException exception) {
            log.warn("publication tick skipped: {}", safeMessage(exception));
        }
    }

    /** 启动及运行期对账：已 ACTIVE 但 Nacos 实例缺失的 MCP/Skill 版本自动补注册。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 5_000L)
    public void reconcileRegistrations() {
        if (!registry.enabled()) {
            return;
        }
        try {
            for (McpComponent component : mcps.list()) {
                for (McpRelease release : mcps.releases(component.id())) {
                    if (!"ACTIVE".equals(release.state())) {
                        continue;
                    }
                    String serviceName =
                            ComponentNames.serviceName(ComponentType.MCP, release.componentKey(), release.version());
                    try {
                        if (!registry.hasHealthy(serviceName)) {
                            registerMcpProjection(release);
                        }
                    } catch (RuntimeException exception) {
                        log.warn(
                                "MCP registration reconcile failed: key={} version={} reason={}",
                                release.componentKey(),
                                release.version(),
                                safeMessage(exception));
                    }
                }
            }
            for (SkillComponent component : skills.list()) {
                for (SkillRelease release : skills.releases(component.id())) {
                    if (!"ACTIVE".equals(release.state())) {
                        continue;
                    }
                    String serviceName =
                            ComponentNames.serviceName(ComponentType.SKILL, release.componentKey(), release.version());
                    try {
                        if (!registry.hasHealthy(serviceName)) {
                            registerSkillProjection(release);
                        }
                    } catch (RuntimeException exception) {
                        log.warn(
                                "Skill registration reconcile failed: key={} version={} reason={}",
                                release.componentKey(),
                                release.version(),
                                safeMessage(exception));
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("registration reconcile skipped: {}", safeMessage(exception));
        }
    }

    private void processOutbox() {
        List<PublicationStore.OutboxEvent> events = publications.claimOutbox(worker, 30, 10);
        for (PublicationStore.OutboxEvent event : events) {
            try {
                UUID publicationId = uuid(event.payload().get("publicationId"));
                if (publicationId == null) {
                    publications.retryOutbox(event.id(), Instant.now().plusSeconds(30));
                    continue;
                }
                Publication publication = publications.find(publicationId).orElse(null);
                if (publication == null) {
                    publications.completeOutbox(event.id());
                    continue;
                }
                Step step = process(publication);
                if (step == Step.DONE) {
                    publications.completeOutbox(event.id());
                } else if (step == Step.WAIT) {
                    publications.retryOutbox(event.id(), Instant.now().plusSeconds(2));
                } else {
                    publications.retryOutbox(event.id(), nextAttempt(publication));
                }
            } catch (RuntimeException exception) {
                publications.retryOutbox(event.id(), Instant.now().plusSeconds(10));
                log.warn("outbox processing failed: id={} reason={}", event.id(), safeMessage(exception));
            }
        }
    }

    private Step process(Publication publication) {
        if ("PUBLISHED".equals(publication.state()) || "FAILED".equals(publication.state())) {
            return Step.DONE;
        }
        if (publication.deadlineAt() != null && publication.deadlineAt().isBefore(Instant.now())) {
            publications.expire(publication.id(), Instant.now());
            return Step.DONE;
        }
        if (publication.nextAttemptAt() != null && publication.nextAttemptAt().isAfter(Instant.now())) {
            return Step.WAIT;
        }
        return switch (publication.phase()) {
            case "VALIDATING" -> validate(publication);
            case "PREPARING" -> prepare(publication);
            case "REGISTERING" -> register(publication);
            default -> {
                retry(publication, "PUBLICATION_STATE_INVALID", "发布阶段未知");
                yield Step.RETRY;
            }
        };
    }

    private Step validate(Publication publication) {
        Long draftRevision = switch (publication.resourceType()) {
            case "MCP" ->
                mcps.find(publication.resourceId()).map(item -> item.revision()).orElse(null);
            case "SKILL" ->
                skills.find(publication.resourceId())
                        .map(item -> item.revision())
                        .orElse(null);
            case "AGENT" ->
                agents.find(publication.resourceId())
                        .map(item -> item.revision())
                        .orElse(null);
            default -> null;
        };
        if (draftRevision == null) {
            failPermanent(publication, ErrorCodes.MISSING_DEPENDENCY, "发布对象已不存在");
            return Step.DONE;
        }
        if (draftRevision != publication.draftRevision()) {
            failPermanent(publication, "STALE_PUBLICATION", "草稿已变化，本次发布不能覆盖新版本");
            return Step.DONE;
        }
        boolean advanced = publications.advance(publication.id(), "VALIDATING", "PREPARING", "PREPARING", null, null);
        return advanced ? Step.WAIT : Step.WAIT;
    }

    private Step prepare(Publication publication) {
        return switch (publication.resourceType()) {
            case "MCP", "SKILL" ->
                publications.advance(publication.id(), "PREPARING", "REGISTERING", "REGISTERING", null, null)
                        ? Step.WAIT
                        : Step.WAIT;
            case "AGENT" -> prepareAgent(publication);
            default -> {
                failPermanent(publication, "PUBLICATION_TYPE_INVALID", "未知发布类型");
                yield Step.DONE;
            }
        };
    }

    private Step prepareAgent(Publication publication) {
        AgentRelease release = agentRelease(publication);
        if (release == null) {
            failPermanent(publication, ErrorCodes.MISSING_DEPENDENCY, "Agent 发布版本不存在");
            return Step.DONE;
        }
        if ("ACTIVE".equals(release.state())) {
            publications.advance(publication.id(), "PREPARING", "REGISTERING", "REGISTERING", null, null);
            return Step.WAIT;
        }
        var targets = runtime.prepareTargets();
        if (targets.isEmpty()) {
            retry(publication, ErrorCodes.NO_HEALTHY_RUNTIME, "没有可用的 Agent Runtime");
            return Step.RETRY;
        }
        RuntimeDispatchClient.RuntimeTarget target =
                targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        RuntimeDispatchClient.PrepareResult result = runtime.prepare(
                target,
                new ReleasePrepareRequest(
                        release.id(), release.manifestDigest(), release.generation(), publication.id()));
        if (!result.ready()) {
            if ("PREPARING".equalsIgnoreCase(result.state())) {
                retry(publication, "RUNTIME_PREPARING", "Runtime 正在准备该版本");
                return Step.WAIT;
            }
            retry(
                    publication,
                    result.errorCode() == null ? "RUNTIME_PREPARE_FAILED" : result.errorCode(),
                    "Runtime 发布准备失败");
            return Step.RETRY;
        }
        boolean advanced =
                publications.advance(publication.id(), "PREPARING", "REGISTERING", "REGISTERING", null, null);
        if (!advanced) {
            return Step.WAIT;
        }
        return Step.RETRY;
    }

    private Step register(Publication publication) {
        return switch (publication.resourceType()) {
            case "MCP" -> registerMcp(publication);
            case "SKILL" -> registerSkill(publication);
            case "AGENT" -> registerAgent(publication);
            default -> {
                failPermanent(publication, "PUBLICATION_TYPE_INVALID", "未知发布类型");
                yield Step.DONE;
            }
        };
    }

    private Step registerMcp(Publication publication) {
        McpRelease release = mcps.releases(publication.resourceId()).stream()
                .filter(item -> item.version().equals(publication.version()))
                .findFirst()
                .orElse(null);
        if (release == null) {
            failPermanent(publication, ErrorCodes.MISSING_DEPENDENCY, "MCP 发布版本不存在");
            return Step.DONE;
        }
        mcps.setReleaseState(release.id(), "ACTIVE");
        mcps.publishComponent(release.componentId(), release.version());
        String serviceName = ComponentNames.serviceName(ComponentType.MCP, release.componentKey(), release.version());
        registerMcpProjection(release);
        projections.ready("MCP", release.id(), serviceName, "control");
        complete(publication);
        return Step.DONE;
    }

    private Step registerSkill(Publication publication) {
        SkillRelease release = skills.releases(publication.resourceId()).stream()
                .filter(item -> item.version().equals(publication.version()))
                .findFirst()
                .orElse(null);
        if (release == null) {
            failPermanent(publication, ErrorCodes.MISSING_DEPENDENCY, "Skill 发布版本不存在");
            return Step.DONE;
        }
        skills.setReleaseState(release.id(), "ACTIVE");
        skills.publishComponent(release.skillId(), release.version());
        String serviceName = ComponentNames.serviceName(ComponentType.SKILL, release.componentKey(), release.version());
        registerSkillProjection(release);
        projections.ready("SKILL", release.id(), serviceName, "control");
        complete(publication);
        return Step.DONE;
    }

    private void registerMcpProjection(McpRelease release) {
        if (!registry.enabled()) {
            return;
        }
        String endpoint = String.valueOf(release.endpointPolicy().getOrDefault("endpoint", ""));
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.unavailable("MCP 入口地址无效：" + release.componentKey());
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw AiExceptions.unavailable("MCP 入口地址无效：" + release.componentKey());
        }
        int endpointPort = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        boolean opsContext = "ops".equalsIgnoreCase(release.componentKey())
                || "OPS"
                        .equalsIgnoreCase(
                                String.valueOf(release.endpointPolicy().get("contextMode")))
                || "OPS_CONTEXT"
                        .equalsIgnoreCase(
                                String.valueOf(release.endpointPolicy().get("authMode")));
        String authMode = opsContext ? "OPS_CONTEXT" : (release.credentialId() == null ? "NONE" : "BEARER");
        McpComponent component = mcps.find(release.componentId()).orElse(null);
        String registryRef = component == null || component.nativeRegistryRef() == null
                ? release.componentKey()
                : String.valueOf(component.nativeRegistryRef().getOrDefault("serverName", release.componentKey()));
        registry.registerComponent(
                ComponentType.MCP,
                release.componentKey(),
                release.version(),
                uri.getScheme(),
                uri.getPath(),
                uri.getHost(),
                endpointPort,
                release.contentDigest(),
                authMode,
                Map.of("transport", "streamable-http", "registryRef", registryRef));
    }

    private void registerSkillProjection(SkillRelease release) {
        if (!registry.enabled()) {
            return;
        }
        registry.registerComponent(
                ComponentType.SKILL,
                release.componentKey(),
                release.version(),
                "http",
                ComponentNames.skillPath(release.componentKey(), release.version()),
                registry.advertiseIp(),
                registry.serverPort(),
                release.bundleDigest(),
                "INTERNAL_HMAC",
                Map.of("mediaType", "text/markdown"));
    }

    private Step registerAgent(Publication publication) {
        AgentRelease release = agentRelease(publication);
        if (release == null) {
            failPermanent(publication, ErrorCodes.MISSING_DEPENDENCY, "Agent 发布版本不存在");
            return Step.DONE;
        }
        String serviceName = ComponentNames.serviceName(ComponentType.AGENT, release.agentKey(), release.version());
        if (registry.enabled() && !registry.hasHealthy(serviceName)) {
            retry(publication, ErrorCodes.REGISTRY_UNAVAILABLE, "注册中心未发现该 Agent 版本的健康实例");
            return Step.RETRY;
        }
        agents.forceReleaseState(release.id(), "ACTIVE");
        agents.publishComponent(release.agentId(), release.version());
        projections.ready("AGENT", release.id(), serviceName, "runtime");
        complete(publication);
        return Step.DONE;
    }

    private void complete(Publication publication) {
        publications.advance(publication.id(), publication.state(), "PUBLISHED", "PUBLISHED", null, null);
    }

    private void retry(Publication publication, String code, String message) {
        publications.fail(publication.id(), code, message, nextAttempt(publication));
    }

    private void failPermanent(Publication publication, String code, String message) {
        publications.markFailed(publication.id(), code, message);
    }

    private Instant nextAttempt(Publication publication) {
        int exponent = Math.min(Math.max(publication.attempts(), 0), 4);
        long seconds = Math.min(16L, 1L << exponent);
        long jitter = ThreadLocalRandom.current().nextLong(700L);
        return Instant.now().plusSeconds(seconds).plusMillis(jitter);
    }

    private AgentRelease agentRelease(Publication publication) {
        return agents.releases(publication.resourceId()).stream()
                .filter(item -> item.version().equals(publication.version()))
                .findFirst()
                .orElse(null);
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private enum Step {
        DONE,
        WAIT,
        RETRY
    }
}
