package top.yealike.ipe.ai.contract;

import java.util.List;
import java.util.UUID;

public record RunEventBatch(
        UUID runId, long executionToken, String instanceId, String requestId, List<RunEvent> events) {

    public RunEventBatch {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
