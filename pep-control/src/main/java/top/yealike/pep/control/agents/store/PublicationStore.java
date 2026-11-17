package top.yealike.pep.control.agents.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.agents.domain.PlatformRecords.Publication;
import top.yealike.pep.control.agents.mapper.PublicationMapper;

@Repository
public class PublicationStore {

    private final PublicationMapper mapper;

    public PublicationStore(PublicationMapper mapper) {
        this.mapper = mapper;
    }

    public UUID insert(
            String resourceType,
            UUID resourceId,
            String version,
            long draftRevision,
            long generation,
            String idempotencyKey,
            Instant deadlineAt) {
        UUID id = UUID.randomUUID();
        mapper.insert(id, resourceType, resourceId, version, draftRevision, generation, idempotencyKey, deadlineAt);
        return id;
    }

    public Optional<Publication> find(UUID id) {
        return mapper.find(id).stream().findFirst();
    }

    public Optional<Publication> findByIdempotency(String resourceType, UUID resourceId, String key) {
        return mapper.findByIdempotency(resourceType, resourceId, key).stream().findFirst();
    }

    public long nextGeneration(String resourceType, UUID resourceId) {
        Long value = mapper.nextGeneration(resourceType, resourceId);
        return value == null ? 1 : value;
    }

    public List<Publication> claimPending(String worker, int leaseSeconds, int limit) {
        mapper.claimPending(worker, leaseSeconds, limit);
        return mapper.claimedByWorker(worker);
    }

    /** 认领指定发布意图；outbox 路径也必须先取得租约，避免与轮询 worker 并发执行同一阶段。 */
    public Optional<Publication> claim(UUID id, String worker, int leaseSeconds) {
        return mapper.claim(id, worker, leaseSeconds) == 0 ? Optional.empty() : find(id);
    }

    /** 批次认领后按条续租；必须在执行任何外部副作用前确认仍持有租约。 */
    public boolean renewLease(UUID id, String worker, int leaseSeconds) {
        return mapper.renewLease(id, worker, leaseSeconds) > 0;
    }

    /**
     * 阶段推进必须由当前租约持有者执行，并以预期状态做 CAS。
     *
     * <p>租约过期后旧 worker 不得再推进或清除新 worker 的租约；调用方在同一数据库事务中完成业务写入和本推进，
     * 本方法返回 false 时必须回滚整个事务。
     */
    public boolean advance(
            UUID id,
            String owner,
            String expectedState,
            String nextState,
            String nextPhase,
            String errorCode,
            String errorMessage) {
        return mapper.advance(id, owner, expectedState, nextState, nextPhase, errorCode, errorMessage) > 0;
    }

    /** @return 是否已达到最大重试次数并进入 FAILED 终态。 */
    public boolean fail(
            UUID id, String owner, String expectedState, String code, String message, Instant nextAttemptAt) {
        int changed = mapper.fail(id, owner, expectedState, code, message, nextAttemptAt);
        if (changed == 0) {
            return false;
        }
        return find(id).map(publication -> "FAILED".equals(publication.state())).orElse(false);
    }

    public void retry(UUID id) {
        mapper.retry(id);
    }

    public boolean expire(UUID id, String owner, String expectedState, Instant deadline) {
        return mapper.expire(id, owner, expectedState, deadline) > 0;
    }

    public boolean markFailed(UUID id, String owner, String expectedState, String code, String message) {
        return mapper.markFailed(id, owner, expectedState, code, message) > 0;
    }

    public UUID insertOutbox(
            String aggregateType, UUID aggregateId, long generation, String eventType, String payloadJson) {
        UUID id = UUID.randomUUID();
        mapper.insertOutbox(id, aggregateType, aggregateId, generation, eventType, payloadJson);
        return id;
    }

    public void markOutboxDone(String aggregateType, UUID aggregateId, long generation, String eventType) {
        mapper.markOutboxDone(aggregateType, aggregateId, generation, eventType);
    }

    public List<OutboxEvent> claimOutbox(String worker, int leaseSeconds, int limit) {
        mapper.claimOutbox(worker, leaseSeconds, limit);
        return mapper.claimedOutbox(worker);
    }

    /** 批次认领后按条续租；续租失败表示租约已被其他 worker 接管。 */
    public boolean renewOutbox(UUID id, String worker, int leaseSeconds) {
        return mapper.renewOutbox(id, worker, leaseSeconds) > 0;
    }

    /** 只有仍持有 outbox 租约的 worker 才能结束事件，租约过期后不得覆盖新 worker 的处理结果。 */
    public boolean completeOutbox(UUID id, String worker) {
        return mapper.completeOutbox(id, worker) > 0;
    }

    /** 只有仍持有 outbox 租约的 worker 才能释放或重排事件。 */
    public boolean retryOutbox(UUID id, String worker, Instant nextAttemptAt) {
        return mapper.retryOutbox(id, worker, nextAttemptAt) > 0;
    }

    public record OutboxEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            long generation,
            String eventType,
            Map<String, Object> payload,
            int attempts) {}
}
