package top.yealike.ipe.ai.run;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.policy.PolicyService;
import top.yealike.ipe.ai.run.RunRecords.Run;

/**
 * Run 调度器。
 *
 * <p>每秒扫描队列，按「会话唯一执行权 + 优先级 + 提交顺序」认领；认领用状态条件更新保证多实例下只有一个执行者。
 * 执行线程与调度线程分离，避免阻塞调度与 SSE 请求线程。
 */
@Service
public class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    private final RunRepository runs;
    private final RunExecutor executor;
    private final AiProperties properties;
    private final PolicyService policy;
    private final top.yealike.ipe.ai.identity.IdentityService identities;
    private final RunEventService events;
    private final AtomicInteger inflight = new AtomicInteger();
    private final String nodeId = nodeId();
    private ExecutorService pool;

    public RunScheduler(
            RunRepository runs,
            RunExecutor executor,
            AiProperties properties,
            PolicyService policy,
            top.yealike.ipe.ai.identity.IdentityService identities,
            RunEventService events) {
        this.runs = runs;
        this.executor = executor;
        this.properties = properties;
        this.policy = policy;
        this.identities = identities;
        this.events = events;
    }

    @PostConstruct
    void start() {
        int concurrency = Math.max(1, properties.run().maxConcurrent());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "ai-run-" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        pool = Executors.newFixedThreadPool(concurrency, factory);
        policy.reload();
        log.info("ai run scheduler started: node={} concurrency={}", nodeId, concurrency);
    }

    @PreDestroy
    void stop() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Scheduled(fixedDelay = 700, initialDelay = 1500)
    public void tick() {
        try {
            dispatch();
        } catch (RuntimeException exception) {
            // 数据库或依赖短暂不可用时只记录，不让调度线程退出；下一轮继续尝试
            log.warn("ai run dispatch skipped: {}", exception.getMessage());
        }
    }

    private void dispatch() {
        int max = Math.max(1, properties.run().maxConcurrent());
        int leaseSeconds = Math.max(60, properties.run().timeoutMs() / 1000 + 60);
        while (inflight.get() < max) {
            var claimed = runs.claimNextQueued(nodeId, leaseSeconds);
            if (claimed.isEmpty()) {
                return;
            }
            Run run = claimed.get();
            inflight.incrementAndGet();
            pool.submit(() -> {
                try {
                    log.info(
                            "run started: run={} conversation={} agent={}",
                            run.id(),
                            run.conversationId(),
                            run.agentKey());
                    UserIdentity identity = identities.resolveActor(run.actor());
                    if (run.actorAuthVersion() == null
                            || run.actorAuthVersion() != identity.authVersion()
                            || !java.util.Set.copyOf(run.actorRoles()).equals(java.util.Set.copyOf(identity.roles())))
                        throw new IllegalStateException("User authorization changed");
                    policy.reload();
                    if (!policy.canUseAgent(identity, run.agentKey()).allowed()) {
                        throw new IllegalStateException("Agent access revoked");
                    }
                    executor.execute(run, identity);
                } catch (RuntimeException exception) {
                    log.error("run crashed: run={}", run.id(), exception);
                    if (runs.isCancellationRequested(run.id())) {
                        runs.markCancelled(run.id(), java.util.Map.of());
                        events.publish(
                                run.conversationId(),
                                run.id(),
                                "run.cancelled",
                                java.util.Map.of("status", RunRecords.CANCELLED));
                    } else if (runs.markFailed(run.id(), "RUN_CRASH", "执行线程异常终止或权限校验失败", java.util.Map.of())) {
                        events.publish(
                                run.conversationId(),
                                run.id(),
                                "run.failed",
                                java.util.Map.of("message", "执行线程异常终止或权限校验失败"));
                    }
                } finally {
                    inflight.decrementAndGet();
                }
            });
        }
    }

    public int inflight() {
        return inflight.get();
    }

    private static String nodeId() {
        String host;
        try {
            host = ManagementFactory.getRuntimeMXBean().getName();
        } catch (RuntimeException exception) {
            host = "local";
        }
        return "ai-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
