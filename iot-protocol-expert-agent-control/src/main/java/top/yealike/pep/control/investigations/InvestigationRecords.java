package top.yealike.pep.control.investigations;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class InvestigationRecords {

    private InvestigationRecords() {}

    public record Task(
            UUID id,
            UUID tenantId,
            UUID projectId,
            String ownerId,
            String title,
            String goal,
            String scope,
            String status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record TaskEvent(
            UUID id, UUID taskId, long seq, String type, String payload, String actorId, OffsetDateTime createdAt) {}

    public record Evidence(
            UUID id,
            UUID tenantId,
            UUID projectId,
            String sourceType,
            String sourceId,
            String sourceVersion,
            String contentHash,
            String summary,
            String rawRef,
            String scope,
            OffsetDateTime collectedAt,
            OffsetDateTime expiresAt,
            String metadata,
            OffsetDateTime createdAt) {}

    public record Hypothesis(
            UUID id,
            UUID taskId,
            String statement,
            String status,
            String supportRefs,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record Experiment(
            UUID id,
            UUID taskId,
            String scenarioReleaseId,
            String simRunId,
            String status,
            String resultSummary,
            String resultRef,
            String expectedAssertion,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record Conclusion(
            UUID id,
            UUID taskId,
            long version,
            String conclusion,
            String evidenceRefs,
            String validationStatus,
            String createdBy,
            OffsetDateTime createdAt) {}

    public record Approval(
            UUID id,
            UUID taskId,
            String actionType,
            String target,
            String paramHash,
            String approvedBy,
            OffsetDateTime expiresAt,
            OffsetDateTime usedAt,
            String state,
            OffsetDateTime createdAt) {}
}
