package top.yealike.ipe.agent.runtime.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 有界线程池：Run 执行、MCP 阻塞调用与心跳调度均显式命名并限制容量。 */
@Configuration
public class RuntimeExecutors {

    private final RuntimeProperties properties;
    private ExecutorService runExecutor;
    private ExecutorService mcpExecutor;
    private ExecutorService reconcileExecutor;
    private ScheduledThreadPoolExecutor heartbeatScheduler;
    private ScheduledThreadPoolExecutor eventScheduler;

    public RuntimeExecutors(RuntimeProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "")
    public ExecutorService runtimeRunExecutor() {
        ThreadFactory factory = named("ai-run-");
        runExecutor = new ThreadPoolExecutor(
                properties.run().maxActive(),
                properties.run().maxActive(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.run().queueCapacity()),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        return runExecutor;
    }

    @Bean(destroyMethod = "")
    public ExecutorService runtimeMcpExecutor() {
        ThreadFactory factory = named("ai-mcp-");
        mcpExecutor = new ThreadPoolExecutor(
                properties.mcp().threads(),
                properties.mcp().threads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.mcp().queueCapacity()),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        return mcpExecutor;
    }

    @Bean(destroyMethod = "")
    public ScheduledThreadPoolExecutor runtimeHeartbeatScheduler() {
        heartbeatScheduler = new ScheduledThreadPoolExecutor(
                Math.max(4, properties.run().maxActive() + properties.run().queueCapacity()), named("ai-heartbeat-"));
        heartbeatScheduler.setRemoveOnCancelPolicy(true);
        return heartbeatScheduler;
    }

    @Bean(destroyMethod = "")
    public ScheduledThreadPoolExecutor runtimeEventScheduler() {
        eventScheduler =
                new ScheduledThreadPoolExecutor(Math.max(2, properties.run().maxActive()), named("ai-event-"));
        eventScheduler.setRemoveOnCancelPolicy(true);
        return eventScheduler;
    }

    @Bean(destroyMethod = "")
    public ExecutorService runtimeReconcileExecutor() {
        reconcileExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                named("ai-reconcile-"),
                new ThreadPoolExecutor.AbortPolicy());
        return reconcileExecutor;
    }

    @PreDestroy
    void shutdown() {
        shutdown(runExecutor);
        shutdown(mcpExecutor);
        shutdown(reconcileExecutor);
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (eventScheduler != null) {
            eventScheduler.shutdownNow();
        }
    }

    private static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static ThreadFactory named(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
