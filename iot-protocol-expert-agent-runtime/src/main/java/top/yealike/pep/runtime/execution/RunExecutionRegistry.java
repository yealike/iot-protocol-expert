package top.yealike.pep.runtime.execution;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 本地 Run 句柄与幂等 receipt；终态保留一段时间供重试查询。 */
@Component
public class RunExecutionRegistry {

    private final ConcurrentHashMap<UUID, RunExecution> byRunId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> dispatchLocks = new ConcurrentHashMap<>();

    public Object dispatchLock(UUID runId) {
        return dispatchLocks.computeIfAbsent(runId, ignored -> new Object());
    }

    public Optional<RunExecution> find(UUID runId) {
        return Optional.ofNullable(byRunId.get(runId));
    }

    public RunExecution register(RunExecution execution) {
        RunExecution previous = byRunId.putIfAbsent(execution.runId(), execution);
        return previous == null ? execution : previous;
    }

    public void remove(UUID runId, RunExecution execution) {
        byRunId.remove(runId, execution);
    }

    public List<RunExecution> all() {
        return List.copyOf(byRunId.values());
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    public void cleanup() {
        Instant threshold = Instant.now().minus(60, ChronoUnit.MINUTES);
        byRunId.entrySet().removeIf(entry -> {
            RunExecution execution = entry.getValue();
            return execution.terminal()
                    && execution.finishedAt() != null
                    && execution.finishedAt().isBefore(threshold);
        });
        dispatchLocks.entrySet().removeIf(entry -> !byRunId.containsKey(entry.getKey()));
    }
}
