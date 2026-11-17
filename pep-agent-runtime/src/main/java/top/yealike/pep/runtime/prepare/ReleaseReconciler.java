package top.yealike.pep.runtime.prepare;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.control.DesiredRelease;
import top.yealike.pep.runtime.control.DesiredReleasePage;
import top.yealike.pep.runtime.registry.NacosRuntimeRegistry;
import top.yealike.pep.runtime.contract.ReleasePrepareRequest;

/**
 * Runtime 重启后的期望版本对账。
 *
 * <p>Runtime 的已准备版本只存在内存；进程重启后必须重新 prepare 并注册精确版本服务，否则 Control 无法派发。
 * 对账失败只记录日志，不阻塞基础健康检查，管理员可重新发布或重启 Runtime 触发再次对账。
 */
@Component
public class ReleaseReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReleaseReconciler.class);
    private static final Duration REGISTRY_WAIT = Duration.ofSeconds(60);
    private static final int MAX_PAGES_PER_RECONCILE = 1_000;

    private final ControlClient control;
    private final PrepareService prepareService;
    private final NacosRuntimeRegistry registry;
    private final ExecutorService executor;
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    public ReleaseReconciler(
            ControlClient control,
            PrepareService prepareService,
            NacosRuntimeRegistry registry,
            @Qualifier("runtimeReconcileExecutor") ExecutorService executor) {
        this.control = control;
        this.prepareService = prepareService;
        this.registry = registry;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void start() {
        submitReconcile();
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 15_000L)
    public void scheduledReconcile() {
        submitReconcile();
    }

    private void submitReconcile() {
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    runSafely();
                } finally {
                    reconciling.set(false);
                }
            });
        } catch (RuntimeException exception) {
            reconciling.set(false);
            log.warn("release reconcile submit failed: reason={}", safeMessage(exception));
        }
    }

    private void runSafely() {
        try {
            if (!awaitRegistry()) {
                log.warn("release reconcile skipped: runtime registry is not ready");
                return;
            }
            int prepared = 0;
            int failed = 0;
            long cursor = 0L;
            int pages = 0;
            boolean truncated = true;
            while (pages < MAX_PAGES_PER_RECONCILE) {
                pages++;
                DesiredReleasePage page = control.desiredReleases(cursor);
                if (page == null || page.items() == null) {
                    throw new ControlException(502, "CONTROL_UNAVAILABLE", "控制面返回无效的版本分页");
                }
                for (DesiredRelease release : page.items()) {
                    if (!"ACTIVE".equalsIgnoreCase(release.state())) {
                        continue;
                    }
                    try {
                        prepareService.prepare(new ReleasePrepareRequest(
                                release.releaseId(),
                                release.manifestDigest(),
                                release.generation(),
                                release.releaseId()));
                        prepared++;
                    } catch (RuntimeException exception) {
                        failed++;
                        log.warn(
                                "release reconcile failed: release={} agent={} version={} code={} reason={}",
                                release.releaseId(),
                                release.agentKey(),
                                release.version(),
                                code(exception),
                                safeMessage(exception));
                    }
                }
                if (page.items().isEmpty()) {
                    truncated = false;
                    break;
                }
                long nextCursor = page.cursor();
                if (nextCursor <= cursor) {
                    truncated = false;
                    log.warn("release reconcile stopped: cursor did not advance from {}", cursor);
                    break;
                }
                if (!page.hasMore()) {
                    truncated = false;
                    break;
                }
                cursor = nextCursor;
            }
            if (truncated) {
                log.warn("release reconcile stopped after {} pages; remaining pages wait for next cycle", pages);
            }
            log.info("release reconcile finished: prepared={} failed={}", prepared, failed);
        } catch (RuntimeException exception) {
            log.warn("release reconcile aborted: code={} reason={}", code(exception), safeMessage(exception));
        }
    }

    private boolean awaitRegistry() {
        if (registry.ready()) {
            return true;
        }
        Instant deadline = Instant.now().plus(REGISTRY_WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                TimeUnit.MILLISECONDS.sleep(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (registry.ready()) {
                return true;
            }
        }
        return registry.ready();
    }

    private static String code(RuntimeException exception) {
        if (exception instanceof PrepareException prepareException) {
            return prepareException.code();
        }
        if (exception instanceof ControlException controlException) {
            return controlException.code();
        }
        return exception.getClass().getSimpleName();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
