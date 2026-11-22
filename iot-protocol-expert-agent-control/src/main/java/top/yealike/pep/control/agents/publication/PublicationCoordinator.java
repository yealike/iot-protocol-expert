package top.yealike.pep.control.agents.publication;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.control.agents.domain.AgentRelease;
import top.yealike.pep.control.agents.domain.McpRelease;
import top.yealike.pep.control.agents.domain.OutboxEvent;
import top.yealike.pep.control.agents.domain.Publication;
import top.yealike.pep.control.agents.domain.SkillRelease;
import top.yealike.pep.control.agents.registry.RegistryProjectionService;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.control.integration.runtime.RuntimeDispatchClient;
import top.yealike.pep.runtime.contract.ComponentNames;
import top.yealike.pep.runtime.contract.ComponentType;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.ReleasePrepareRequest;

/** 发布协调：outbox 触发 + publication 轮询，按阶段幂等推进，失败指数退避。 */
@Service
public class PublicationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PublicationCoordinator.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;

    private final PublicationStore publications;
    private final McpStore mcps;
    private final SkillStore skills;
    private final AgentStore agents;
    private final RuntimeDispatchClient runtime;
    private final RegistryProjectionService projections;
    private final String worker;
    private final TransactionTemplate transactions;

    public PublicationCoordinator(
            PublicationStore publications,
            McpStore mcps,
            SkillStore skills,
            AgentStore agents,
            RuntimeDispatchClient runtime,
            RegistryProjectionService projections,
            PlatformTransactionManager transactionManager) {
        this.publications = publications;
        this.mcps = mcps;
        this.skills = skills;
        this.agents = agents;
        this.runtime = runtime;
        this.projections = projections;
        this.transactions = new TransactionTemplate(transactionManager);
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
            for (Publication publication : publications.claimPending(worker, LEASE_SECONDS, 20)) {
                if (!publications.renewLease(publication.id(), worker, LEASE_SECONDS)) {
                    continue;
                }
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

    private void processOutbox() {
        List<OutboxEvent> events = publications.claimOutbox(worker, LEASE_SECONDS, 10);
        for (OutboxEvent event : events) {
            if (!publications.renewOutbox(event.id(), worker, LEASE_SECONDS)) {
                continue;
            }
            try {
                UUID publicationId = uuid(event.payload().get("publicationId"));
                if (publicationId == null) {
                    publications.retryOutbox(event.id(), worker, Instant.now().plusSeconds(30));
                    continue;
                }
                Publication publication = publications.find(publicationId).orElse(null);
                if (publication == null) {
                    publications.completeOutbox(event.id(), worker);
                    continue;
                }
                if ("PUBLISHED".equals(publication.state()) || "FAILED".equals(publication.state())) {
                    publications.completeOutbox(event.id(), worker);
                    continue;
                }
                publication =
                        publications.claim(publicationId, worker, LEASE_SECONDS).orElse(null);
                if (publication == null) {
                    publications.retryOutbox(event.id(), worker, Instant.now().plusSeconds(2));
                    continue;
                }
                Step step = process(publication);
                if (step == Step.DONE) {
                    publications.completeOutbox(event.id(), worker);
                } else if (step == Step.WAIT) {
                    publications.retryOutbox(event.id(), worker, Instant.now().plusSeconds(2));
                } else {
                    publications.retryOutbox(event.id(), worker, nextAttempt(publication));
                }
            } catch (RuntimeException exception) {
                publications.retryOutbox(event.id(), worker, Instant.now().plusSeconds(10));
                log.warn("outbox processing failed: id={} reason={}", event.id(), safeMessage(exception));
            }
        }
    }

    private Step process(Publication publication) {
        if ("PUBLISHED".equals(publication.state()) || "FAILED".equals(publication.state())) {
            return Step.DONE;
        }
        if (publication.deadlineAt() != null && publication.deadlineAt().isBefore(Instant.now())) {
            publications.expire(publication.id(), worker, publication.state(), Instant.now());
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
        boolean advanced =
                publications.advance(publication.id(), worker, "VALIDATING", "PREPARING", "PREPARING", null, null);
        return advanced ? Step.WAIT : Step.WAIT;
    }

    private Step prepare(Publication publication) {
        return switch (publication.resourceType()) {
            case "MCP", "SKILL" ->
                publications.advance(publication.id(), worker, "PREPARING", "REGISTERING", "REGISTERING", null, null)
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
        var targets = runtime.targets();
        if (targets.isEmpty()) {
            retry(publication, ErrorCodes.NO_HEALTHY_RUNTIME, "没有可用的 Agent Runtime");
            return Step.RETRY;
        }
        RuntimeDispatchClient.RuntimeTarget target = targets.getFirst();
        RuntimeDispatchClient.PrepareResult result = runtime.prepare(
                target,
                new ReleasePrepareRequest(
                        release.id(), release.manifestDigest(), release.generation(), publication.id()));
        if (!result.ready()) {
            if ("PREPARING".equalsIgnoreCase(result.state())) {
                retry(publication, "RUNTIME_PREPARING", "Runtime 正在准备该版本");
                return Step.WAIT;
            }
            String code = result.errorCode() == null ? "RUNTIME_PREPARE_FAILED" : result.errorCode();
            String message =
                    result.errorMessage() == null || result.errorMessage().isBlank()
                            ? "Runtime 发布准备失败"
                            : result.errorMessage();
            if (result.status() == 422 || ErrorCodes.REVISION_CONFLICT.equals(code)) {
                failPermanent(publication, code, message);
                return Step.DONE;
            }
            retry(publication, code, message);
            return Step.RETRY;
        }
        return activateAgent(publication, release);
    }

    private Step register(Publication publication) {
        return switch (publication.resourceType()) {
            case "MCP" -> registerMcp(publication);
            case "SKILL" -> registerSkill(publication);
            case "AGENT" -> prepareAgent(publication);
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
        String serviceName = ComponentNames.serviceName(ComponentType.MCP, release.componentKey(), release.version());
        boolean completed = Boolean.TRUE.equals(transactions.execute(status -> {
            if ((mcps.setReleaseState(release.id(), "ACTIVE") == 0)
                    || !mcps.publishComponent(release.componentId(), release.version(), publication.generation())) {
                status.setRollbackOnly();
                return false;
            }
            projections.ready("MCP", release.id(), serviceName, "control");
            if (!complete(publication)) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
        if (!completed) {
            retry(publication, ErrorCodes.REVISION_CONFLICT, "MCP 默认版本已被更新的发布意图修改");
            return Step.RETRY;
        }
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
        String serviceName = ComponentNames.serviceName(ComponentType.SKILL, release.componentKey(), release.version());
        boolean completed = Boolean.TRUE.equals(transactions.execute(status -> {
            if ((skills.setReleaseState(release.id(), "ACTIVE") == 0)
                    || !skills.publishComponent(release.skillId(), release.version(), publication.generation())) {
                status.setRollbackOnly();
                return false;
            }
            projections.ready("SKILL", release.id(), serviceName, "control");
            if (!complete(publication)) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
        if (!completed) {
            retry(publication, ErrorCodes.REVISION_CONFLICT, "Skill 默认版本已被更新的发布意图修改");
            return Step.RETRY;
        }
        return Step.DONE;
    }

    private Step activateAgent(Publication publication, AgentRelease release) {
        String serviceName = ComponentNames.serviceName(ComponentType.AGENT, release.agentKey(), release.version());
        boolean completed = Boolean.TRUE.equals(transactions.execute(status -> {
            if ((agents.setReleaseState(release.id(), "ACTIVE") == 0)
                    || !agents.publishComponent(release.agentId(), release.version(), publication.generation())) {
                status.setRollbackOnly();
                return false;
            }
            projections.ready("AGENT", release.id(), serviceName, "runtime");
            if (!complete(publication)) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
        if (!completed) {
            retry(publication, ErrorCodes.REVISION_CONFLICT, "Agent 默认版本已被更新的发布意图修改");
            return Step.RETRY;
        }
        return Step.DONE;
    }

    private boolean complete(Publication publication) {
        return publications.advance(
                publication.id(), worker, publication.state(), "PUBLISHED", "PUBLISHED", null, null);
    }

    private void retry(Publication publication, String code, String message) {
        publications.fail(publication.id(), worker, publication.state(), code, message, nextAttempt(publication));
    }

    private void failPermanent(Publication publication, String code, String message) {
        publications.markFailed(publication.id(), worker, publication.state(), code, message);
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
