package top.yealike.ipe.ai.platform.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.Publication;
import top.yealike.ipe.ai.support.JsonSupport;

@Repository
public class PublicationStore {

    private static final String SELECT = """
            select id, resource_type, resource_id, version, draft_revision, state, phase, generation,
                   idempotency_key, error_code, error_message, attempts, next_attempt_at, deadline_at,
                   created_at, updated_at
              from ai_publication
            """;

    private final JdbcTemplate jdbc;

    public PublicationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        jdbc.update(
                "insert into ai_publication (id, resource_type, resource_id, version, draft_revision, state, phase, generation, idempotency_key, deadline_at) values (?, ?, ?, ?, ?, 'VALIDATING', 'VALIDATING', ?, ?, ?)",
                id,
                resourceType,
                resourceId,
                version,
                draftRevision,
                generation,
                idempotencyKey,
                Timestamp.from(deadlineAt));
        return id;
    }

    public Optional<Publication> find(UUID id) {
        return jdbc.query(SELECT + " where id = ?", (rows, index) -> map(rows), id).stream()
                .findFirst();
    }

    public Optional<Publication> findByIdempotency(String resourceType, UUID resourceId, String key) {
        return jdbc
                .query(
                        SELECT + " where resource_type = ? and resource_id = ? and idempotency_key = ?",
                        (rows, index) -> map(rows),
                        resourceType,
                        resourceId,
                        key)
                .stream()
                .findFirst();
    }

    public long nextGeneration(String resourceType, UUID resourceId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(generation), 0) + 1 from ai_publication where resource_type = ? and resource_id = ?",
                Long.class,
                resourceType,
                resourceId);
        return value == null ? 1 : value;
    }

    public List<Publication> claimPending(String worker, int leaseSeconds, int limit) {
        jdbc.update("""
                update ai_publication
                   set lease_owner = ?, lease_until = now() + make_interval(secs => ?), updated_at = now()
                 where id in (
                       select id from ai_publication
                        where state in ('VALIDATING', 'PREPARING', 'REGISTERING')
                          and next_attempt_at <= now()
                          and (lease_until is null or lease_until < now())
                        order by created_at
                        limit ?
                          for update skip locked)
                """, worker, leaseSeconds, limit);
        return jdbc.query(
                SELECT
                        + " where lease_owner = ? and state in ('VALIDATING', 'PREPARING', 'REGISTERING') order by created_at",
                (rows, index) -> map(rows),
                worker);
    }

    /** 认领指定发布意图；outbox 路径也必须先取得租约，避免与轮询 worker 并发执行同一阶段。 */
    public Optional<Publication> claim(UUID id, String worker, int leaseSeconds) {
        int changed = jdbc.update("""
                update ai_publication
                   set lease_owner = ?, lease_until = now() + make_interval(secs => ?), updated_at = now()
                 where id = ?
                   and state in ('VALIDATING', 'PREPARING', 'REGISTERING')
                   and next_attempt_at <= now()
                   and (lease_until is null or lease_until < now())
                """, worker, leaseSeconds, id);
        if (changed == 0) {
            return Optional.empty();
        }
        return find(id);
    }

    public boolean advance(
            UUID id, String expectedState, String nextState, String nextPhase, String errorCode, String errorMessage) {
        return jdbc.update(
                        "update ai_publication set state = ?, phase = ?, error_code = ?, error_message = ?, lease_owner = null, lease_until = null, updated_at = now() where id = ? and state = ?",
                        nextState,
                        nextPhase,
                        errorCode,
                        errorMessage,
                        id,
                        expectedState)
                > 0;
    }

    /** @return 是否已达到最大重试次数并进入 FAILED 终态。 */
    public boolean fail(UUID id, String code, String message, Instant nextAttemptAt) {
        int changed = jdbc.update(
                "update ai_publication set state = case when attempts + 1 >= 5 then 'FAILED' else state end, error_code = ?, error_message = ?, attempts = attempts + 1, next_attempt_at = ?, lease_owner = null, lease_until = null, updated_at = now() where id = ?",
                code,
                message,
                Timestamp.from(nextAttemptAt),
                id);
        if (changed == 0) {
            return false;
        }
        return find(id).map(publication -> "FAILED".equals(publication.state())).orElse(false);
    }

    public void retry(UUID id) {
        jdbc.update(
                "update ai_publication set state = 'VALIDATING', phase = 'VALIDATING', error_code = null, error_message = null, attempts = 0, next_attempt_at = now(), deadline_at = now() + interval '60 seconds', lease_owner = null, lease_until = null, updated_at = now() where id = ? and state = 'FAILED'",
                id);
    }

    public boolean expire(UUID id, Instant deadline) {
        return jdbc.update(
                        "update ai_publication set state = 'FAILED', error_code = 'PUBLICATION_TIMEOUT', error_message = '发布超时，请检查注册或运行时状态后重试', lease_owner = null, lease_until = null, updated_at = now() where id = ? and state in ('VALIDATING', 'PREPARING', 'REGISTERING') and deadline_at < ?",
                        id,
                        Timestamp.from(deadline))
                > 0;
    }

    public void markFailed(UUID id, String code, String message) {
        jdbc.update(
                "update ai_publication set state = 'FAILED', error_code = ?, error_message = ?, lease_owner = null, lease_until = null, updated_at = now() where id = ?",
                code,
                message,
                id);
    }

    public UUID insertOutbox(
            String aggregateType, UUID aggregateId, long generation, String eventType, String payloadJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_outbox (id, aggregate_type, aggregate_id, generation, event_type, payload) values (?, ?, ?, ?, ?, cast(? as jsonb)) on conflict (aggregate_type, aggregate_id, generation, event_type) do nothing",
                id,
                aggregateType,
                aggregateId,
                generation,
                eventType,
                payloadJson);
        return id;
    }

    public void markOutboxDone(String aggregateType, UUID aggregateId, long generation, String eventType) {
        jdbc.update(
                "update ai_outbox set status = 'DONE' where aggregate_type = ? and aggregate_id = ? and generation = ? and event_type = ?",
                aggregateType,
                aggregateId,
                generation,
                eventType);
    }

    public List<OutboxEvent> claimOutbox(String worker, int leaseSeconds, int limit) {
        jdbc.update("""
                update ai_outbox
                   set lease_owner = ?, lease_until = now() + make_interval(secs => ?), attempts = attempts + 1
                 where id in (
                       select id from ai_outbox
                        where status = 'PENDING' and next_attempt_at <= now()
                          and (lease_until is null or lease_until < now())
                        order by created_at
                        limit ?
                          for update skip locked)
                """, worker, leaseSeconds, limit);
        return jdbc.query(
                """
                select id, aggregate_type, aggregate_id, generation, event_type, payload, attempts
                  from ai_outbox
                 where lease_owner = ? and status = 'PENDING'
                 order by created_at
                """,
                (rows, index) -> new OutboxEvent(
                        JdbcRows.uuid(rows, "id"),
                        rows.getString("aggregate_type"),
                        JdbcRows.uuid(rows, "aggregate_id"),
                        rows.getLong("generation"),
                        rows.getString("event_type"),
                        JsonSupport.readMap(rows.getString("payload")),
                        rows.getInt("attempts")),
                worker);
    }

    public void completeOutbox(UUID id) {
        jdbc.update("update ai_outbox set status = 'DONE', lease_owner = null, lease_until = null where id = ?", id);
    }

    public void retryOutbox(UUID id, Instant nextAttemptAt) {
        jdbc.update(
                "update ai_outbox set lease_owner = null, lease_until = null, next_attempt_at = ?, status = case when attempts >= 8 then 'FAILED' else 'PENDING' end where id = ?",
                Timestamp.from(nextAttemptAt),
                id);
    }

    private Publication map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Publication(
                JdbcRows.uuid(rows, "id"),
                rows.getString("resource_type"),
                JdbcRows.uuid(rows, "resource_id"),
                rows.getString("version"),
                rows.getLong("draft_revision"),
                rows.getString("state"),
                rows.getString("phase"),
                rows.getLong("generation"),
                rows.getString("idempotency_key"),
                rows.getString("error_code"),
                rows.getString("error_message"),
                rows.getInt("attempts"),
                JdbcRows.instant(rows, "next_attempt_at"),
                JdbcRows.instant(rows, "deadline_at"),
                JdbcRows.instant(rows, "created_at"),
                JdbcRows.instant(rows, "updated_at"));
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
