package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunCompletionRequest(
        UUID runId,
        long executionToken,
        String instanceId,
        String requestId,
        RunTerminalStatus status,
        String answer,
        List<Map<String, Object>> citations,
        List<String> warnings,
        Map<String, Object> usage,
        String errorCode,
        String errorMessage) {

    public RunCompletionRequest {
        citations = citations == null ? List.of() : List.copyOf(citations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }
}
