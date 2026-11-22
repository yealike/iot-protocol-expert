package top.yealike.pep.control.agents.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.agents.domain.OutboxEvent;
import top.yealike.pep.control.agents.domain.Publication;

public interface PublicationMapper {

    int insert(
            @Param("id") UUID id,
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId,
            @Param("version") String version,
            @Param("draftRevision") long draftRevision,
            @Param("generation") long generation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("deadlineAt") Instant deadlineAt);

    List<Publication> find(@Param("id") UUID id);

    List<Publication> findByIdempotency(
            @Param("resourceType") String resourceType, @Param("resourceId") UUID resourceId, @Param("key") String key);

    Long nextGeneration(@Param("resourceType") String resourceType, @Param("resourceId") UUID resourceId);

    int claimPending(
            @Param("worker") String worker, @Param("leaseSeconds") int leaseSeconds, @Param("limit") int limit);

    List<Publication> claimedByWorker(@Param("worker") String worker);

    int claim(@Param("id") UUID id, @Param("worker") String worker, @Param("leaseSeconds") int leaseSeconds);

    int renewLease(@Param("id") UUID id, @Param("worker") String worker, @Param("leaseSeconds") int leaseSeconds);

    int advance(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("expectedState") String expectedState,
            @Param("nextState") String nextState,
            @Param("nextPhase") String nextPhase,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    int fail(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("expectedState") String expectedState,
            @Param("code") String code,
            @Param("message") String message,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    int retry(@Param("id") UUID id);

    int expire(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("expectedState") String expectedState,
            @Param("deadline") Instant deadline);

    int markFailed(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("expectedState") String expectedState,
            @Param("code") String code,
            @Param("message") String message);

    int insertOutbox(
            @Param("id") UUID id,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("generation") long generation,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson);

    int markOutboxDone(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("generation") long generation,
            @Param("eventType") String eventType);

    int claimOutbox(@Param("worker") String worker, @Param("leaseSeconds") int leaseSeconds, @Param("limit") int limit);

    List<OutboxEvent> claimedOutbox(@Param("worker") String worker);

    int renewOutbox(@Param("id") UUID id, @Param("worker") String worker, @Param("leaseSeconds") int leaseSeconds);

    int completeOutbox(@Param("id") UUID id, @Param("worker") String worker);

    int retryOutbox(
            @Param("id") UUID id, @Param("worker") String worker, @Param("nextAttemptAt") Instant nextAttemptAt);
}
