package top.yealike.ipe.ai.run;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunRecords.RunEvent;
import top.yealike.ipe.ai.run.RunRecords.ToolCall;
import top.yealike.ipe.ai.support.JsonSupport;

/** Run / 事件 / 工具调用的 SQL 访问层。 */
@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRun(
            UUID id,
            UUID conversationId,
            UUID userMessageId,
            String actor,
            List<String> actorRoles,
            long actorAuthVersion,
            String question,
            String agentKey,
            String agentVersion,
            String modelKey,
            String idempotencyKey,
            String policyVersion,
            int priority,
            Instant createdAt) {
        jdbc.update(
                """
                insert into ai_run (id, conversation_id, user_message_id, actor, actor_roles, actor_auth_version, question, agent_key,
                                    agent_version, model_key, status, priority, idempotency_key, policy_version,
                                    created_at, updated_at)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                conversationId,
                userMessageId,
                actor,
                JsonSupport.write(actorRoles == null ? List.of() : actorRoles),
                actorAuthVersion,
                question,
                agentKey,
                agentVersion,
                modelKey,
                RunRecords.QUEUED,
                priority,
                idempotencyKey,
                policyVersion,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    public Optional<Run> find(UUID id) {
        return jdbc.query("select * from ai_run where id = ?", RUN_MAPPER, id).stream()
                .findFirst();
    }

    public Optional<Run> findByIdempotency(UUID conversationId, String idempotencyKey) {
        return jdbc
                .query(
                        "select * from ai_run where conversation_id = ? and idempotency_key = ?",
                        RUN_MAPPER,
                        conversationId,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    public Optional<Run> latest(UUID conversationId) {
        return jdbc
                .query(
                        "select * from ai_run where conversation_id = ? order by created_at desc limit 1",
                        RUN_MAPPER,
                        conversationId)
                .stream()
                .findFirst();
    }

    public Optional<Run> current(UUID conversationId) {
        return jdbc
                .query(
                        "select * from ai_run where conversation_id = ? and status in (?, ?, ?, ?, ?) "
                                + "order by case when status in ('RUNNING', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED') then 0 "
                                + "when status = 'PAUSED' then 1 else 2 end, priority desc, queue_seq asc limit 1",
                        RUN_MAPPER,
                        conversationId,
                        RunRecords.QUEUED,
                        RunRecords.RUNNING,
                        RunRecords.PAUSE_REQUESTED,
                        RunRecords.PAUSED,
                        RunRecords.CANCEL_REQUESTED)
                .stream()
                .findFirst();
    }

    public List<Run> listByConversation(UUID conversationId, int limit) {
        return jdbc.query(
                "select * from ai_run where conversation_id = ? order by created_at desc limit ?",
                RUN_MAPPER,
                conversationId,
                limit);
    }

    /**
     * 选出下一个可执行的 Run：排除仍持有执行权的会话，再原子认领（status 条件是并发保护）。
     *
     * <p>按优先级与提交顺序取队首；认领失败直接返回空，等下一轮调度。
     */
    public Optional<Run> claimNextQueued(String leaseOwner, int leaseSeconds) {
        List<UUID> candidates = jdbc.query(
                """
                select c.id from ai_run c
                where c.status = ?
                  and not exists (
                      select 1 from ai_run x
                      where x.conversation_id = c.conversation_id
                        and x.status in (?, ?, ?))
                order by c.priority desc, c.queue_seq asc
                limit 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                RunRecords.QUEUED,
                RunRecords.RUNNING,
                RunRecords.PAUSE_REQUESTED,
                RunRecords.CANCEL_REQUESTED);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID id = candidates.getFirst();
        int updated = jdbc.update("""
                update ai_run
                set status = ?, lease_owner = ?, lease_until = now() + make_interval(secs => ?),
                    started_at = coalesce(started_at, now()), updated_at = now()
                where id = ? and status = ?
                """, RunRecords.RUNNING, leaseOwner, leaseSeconds, id, RunRecords.QUEUED);
        if (updated == 0) {
            return Optional.empty();
        }
        return find(id);
    }

    public void renewLease(UUID id, String leaseOwner, int leaseSeconds) {
        jdbc.update(
                "update ai_run set lease_until = now() + make_interval(secs => ?), updated_at = now() "
                        + "where id = ? and lease_owner = ? and status in (?, ?)",
                leaseSeconds,
                id,
                leaseOwner,
                RunRecords.RUNNING,
                RunRecords.PAUSE_REQUESTED);
    }

    public boolean transition(UUID id, String expected, String next) {
        return jdbc.update(
                        "update ai_run set status = ?, updated_at = now() where id = ? and status = ?",
                        next,
                        id,
                        expected)
                > 0;
    }

    public boolean requestPause(UUID id) {
        return jdbc.update(
                        "update ai_run set status = ?, updated_at = now() where id = ? and status = ?",
                        RunRecords.PAUSE_REQUESTED,
                        id,
                        RunRecords.RUNNING)
                > 0;
    }

    public boolean markCompleted(UUID id, Map<String, Object> usage) {
        return jdbc.update(
                        "update ai_run set status = ?, usage_json = cast(? as jsonb), finished_at = now(), updated_at = now(), "
                                + "lease_owner = null, lease_until = null where id = ? and status in (?, ?, ?)",
                        RunRecords.COMPLETED,
                        JsonSupport.write(usage),
                        id,
                        RunRecords.RUNNING,
                        RunRecords.PAUSE_REQUESTED,
                        RunRecords.QUEUED)
                > 0;
    }

    public boolean markFailed(UUID id, String code, String message, Map<String, Object> usage) {
        return jdbc.update(
                        "update ai_run set status = ?, error_code = ?, error_message = ?, usage_json = cast(? as jsonb), "
                                + "finished_at = now(), updated_at = now(), lease_owner = null, lease_until = null "
                                + "where id = ? and status in (?, ?)",
                        RunRecords.FAILED,
                        code,
                        message,
                        JsonSupport.write(usage),
                        id,
                        RunRecords.RUNNING,
                        RunRecords.PAUSE_REQUESTED)
                > 0;
    }

    public void markCancelled(UUID id, Map<String, Object> usage) {
        jdbc.update(
                "update ai_run set status = ?, usage_json = cast(? as jsonb), finished_at = now(), updated_at = now(), "
                        + "lease_owner = null, lease_until = null where id = ? and status not in (?, ?)",
                RunRecords.CANCELLED,
                JsonSupport.write(usage),
                id,
                RunRecords.COMPLETED,
                RunRecords.FAILED);
    }

    public boolean markPaused(UUID id, Map<String, Object> usage) {
        return jdbc.update(
                        "update ai_run set status = ?, usage_json = cast(? as jsonb), updated_at = now(), "
                                + "lease_owner = null, lease_until = null where id = ? and status = ?",
                        RunRecords.PAUSED,
                        JsonSupport.write(usage),
                        id,
                        RunRecords.PAUSE_REQUESTED)
                > 0;
    }

    /** 恢复：PAUSED → QUEUED，保留原 queue_seq，使其排在恢复之后提交的新问题之前。 */
    public boolean requeuePaused(UUID id) {
        return jdbc.update(
                        "update ai_run set status = ?, error_code = null, error_message = null, updated_at = now() "
                                + "where id = ? and status = ?",
                        RunRecords.QUEUED,
                        id,
                        RunRecords.PAUSED)
                > 0;
    }

    public void requestCancel(UUID id) {
        jdbc.update("""
                update ai_run set status = case when status in ('RUNNING', 'PAUSE_REQUESTED')
                    then 'CANCEL_REQUESTED' else 'CANCELLED' end,
                    finished_at = case when status in ('QUEUED', 'PAUSED') then now() else finished_at end,
                    updated_at = now()
                where id = ? and status in ('QUEUED', 'RUNNING', 'PAUSE_REQUESTED', 'PAUSED')
                """, id);
    }

    public void cancelQueued(UUID id) {
        jdbc.update(
                "update ai_run set status = ?, finished_at = now(), updated_at = now() where id = ? and status = ?",
                RunRecords.CANCELLED,
                id,
                RunRecords.QUEUED);
    }

    public int countRunning() {
        Integer count = jdbc.queryForObject(
                "select count(*) from ai_run where status in (?, ?, ?)",
                Integer.class,
                RunRecords.RUNNING,
                RunRecords.PAUSE_REQUESTED,
                RunRecords.CANCEL_REQUESTED);
        return count == null ? 0 : count;
    }

    public boolean isCancellationRequested(UUID id) {
        String status =
                jdbc
                        .query("select status from ai_run where id = ?", (rs, rowNum) -> rs.getString("status"), id)
                        .stream()
                        .findFirst()
                        .orElse(null);
        return RunRecords.CANCEL_REQUESTED.equals(status) || RunRecords.CANCELLED.equals(status);
    }

    public boolean isPauseRequested(UUID id) {
        String status =
                jdbc
                        .query("select status from ai_run where id = ?", (rs, rowNum) -> rs.getString("status"), id)
                        .stream()
                        .findFirst()
                        .orElse(null);
        return RunRecords.PAUSE_REQUESTED.equals(status);
    }

    // ---------------------------------------------------------------- events

    public long nextEventCursor(UUID conversationId) {
        Long cursor = jdbc.queryForObject(
                "update ai_conversation set event_cursor = event_cursor + 1 where id = ? returning event_cursor",
                Long.class,
                conversationId);
        if (cursor == null) {
            throw new IllegalStateException("conversation not found: " + conversationId);
        }
        return cursor;
    }

    public void insertEvent(
            UUID conversationId, UUID runId, long cursor, String type, Map<String, Object> payload, Instant createdAt) {
        jdbc.update(
                "insert into ai_run_event (conversation_id, run_id, cursor, type, payload, created_at) "
                        + "values (?, ?, ?, ?, cast(? as jsonb), ?)",
                conversationId,
                runId,
                cursor,
                type,
                JsonSupport.write(payload),
                Timestamp.from(createdAt));
    }

    public List<RunEvent> eventsAfter(UUID conversationId, long cursor, int limit) {
        return jdbc.query(
                "select * from ai_run_event where conversation_id = ? and cursor > ? order by cursor asc limit ?",
                EVENT_MAPPER,
                conversationId,
                cursor,
                limit);
    }

    public long latestCursor(UUID conversationId) {
        Long cursor = jdbc.queryForObject(
                "select coalesce(max(cursor), 0) from ai_run_event where conversation_id = ?",
                Long.class,
                conversationId);
        return cursor == null ? 0 : cursor;
    }

    // ------------------------------------------------------------ tool calls

    public void insertToolCall(
            UUID id,
            UUID runId,
            UUID conversationId,
            String toolName,
            String decision,
            String denyReason,
            Map<String, Object> argsSummary,
            Instant createdAt) {
        jdbc.update(
                """
                insert into ai_tool_call (id, run_id, conversation_id, tool_name, status, decision, deny_reason,
                                          args_summary, created_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                id,
                runId,
                conversationId,
                toolName,
                decision.equals("ALLOW") ? "SENT" : "DENIED",
                decision,
                denyReason,
                JsonSupport.write(argsSummary == null ? Map.of() : argsSummary),
                Timestamp.from(createdAt));
    }

    public void finishToolCall(UUID id, String status, Map<String, Object> resultSummary, int durationMs) {
        jdbc.update(
                "update ai_tool_call set status = ?, result_summary = cast(? as jsonb), duration_ms = ?, "
                        + "finished_at = now() where id = ?",
                status,
                JsonSupport.write(resultSummary == null ? Map.of() : resultSummary),
                durationMs,
                id);
    }

    public List<ToolCall> listToolCalls(UUID runId) {
        return jdbc.query(
                "select * from ai_tool_call where run_id = ? order by created_at asc", TOOL_CALL_MAPPER, runId);
    }

    public Optional<RunEvent> eventByCursor(UUID conversationId, long cursor) {
        return jdbc
                .query(
                        "select * from ai_run_event where conversation_id = ? and cursor = ?",
                        EVENT_MAPPER,
                        conversationId,
                        cursor)
                .stream()
                .findFirst();
    }

    private static final RowMapper<Run> RUN_MAPPER = (rs, rowNum) -> new Run(
            rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("user_message_id", UUID.class),
            rs.getString("actor"),
            JsonSupport.readStringList(rs.getString("actor_roles")),
            rs.getObject("actor_auth_version", Long.class),
            rs.getString("question"),
            rs.getString("agent_key"),
            rs.getString("agent_version"),
            rs.getString("model_key"),
            rs.getString("status"),
            rs.getInt("priority"),
            rs.getLong("queue_seq"),
            rs.getString("idempotency_key"),
            rs.getString("policy_version"),
            rs.getString("lease_owner"),
            instant(rs.getTimestamp("lease_until")),
            JsonSupport.readMap(rs.getString("budget_json")),
            JsonSupport.readMap(rs.getString("usage_json")),
            rs.getString("error_code"),
            rs.getString("error_message"),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<RunEvent> EVENT_MAPPER = (rs, rowNum) -> new RunEvent(
            rs.getLong("cursor"),
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getString("type"),
            JsonSupport.readMap(rs.getString("payload")),
            instant(rs.getTimestamp("created_at")));

    private static final RowMapper<ToolCall> TOOL_CALL_MAPPER = (rs, rowNum) -> new ToolCall(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            rs.getString("tool_name"),
            rs.getString("status"),
            rs.getString("decision"),
            rs.getString("deny_reason"),
            JsonSupport.readMap(rs.getString("args_summary")),
            JsonSupport.readMap(rs.getString("result_summary")),
            rs.getObject("duration_ms") == null ? null : rs.getInt("duration_ms"),
            rs.getString("trace_id"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("finished_at")));

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
