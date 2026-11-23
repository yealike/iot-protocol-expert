package top.yealike.pep.control.conversations.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import top.yealike.pep.runtime.contract.RunCitation;
import top.yealike.pep.runtime.contract.RunUsage;

public record Message(
        UUID id,
        UUID conversationId,
        UUID runId,
        String role,
        String content,
        List<RunCitation> citations,
        String status,
        long seq,
        Instant createdAt,
        RunUsage usage) {}
