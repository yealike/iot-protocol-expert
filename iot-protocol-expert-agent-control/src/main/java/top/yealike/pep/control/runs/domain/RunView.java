package top.yealike.pep.control.runs.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.RunUsage;

public record RunView(
        UUID id,
        UUID conversationId,
        String question,
        String agentKey,
        String agentVersion,
        String manifestDigest,
        String traceId,
        String status,
        int priority,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        RunUsage usage,
        List<ToolCallView> toolCalls) {

    public static RunView from(Run run, List<ToolCall> toolCalls) {
        RunUsage usage = run.usage() == null || run.usage().isEmpty()
                ? null
                : JsonSupport.read(JsonSupport.write(run.usage()), RunUsage.class);
        return new RunView(
                run.id(),
                run.conversationId(),
                run.question(),
                run.agentKey(),
                run.agentVersion(),
                run.manifestDigest(),
                run.traceContext(),
                run.status(),
                run.priority(),
                run.errorCode(),
                run.errorMessage(),
                run.startedAt(),
                run.finishedAt(),
                run.createdAt(),
                usage,
                toolCalls == null
                        ? List.of()
                        : toolCalls.stream().map(ToolCallView::from).toList());
    }
}
