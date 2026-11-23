package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.UUID;

public record RunCompletionRequest(
        UUID runId,
        long executionToken,
        String instanceId,
        String requestId,
        RunTerminalStatus status,
        String answer,
        List<RunCitation> citations,
        List<String> warnings,
        RunUsage usage,
        String errorCode,
        String errorMessage) {

    public RunCompletionRequest {
        citations = citations == null ? List.of() : List.copyOf(citations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
