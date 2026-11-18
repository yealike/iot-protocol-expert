package top.yealike.pep.modelgateway.quota;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaRepository {

    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OffsetDateTime now() {
        return jdbc.queryForObject("select now()", OffsetDateTime.class);
    }

    public void lockAdvisory(String value) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, value);
    }

    public List<QuotaRecords.Account> lockAccounts(String scopeKind, String subjectId) {
        return jdbc.query("""
                select id, scope_kind, subject_id, policy_key, active_revision, current_bucket_id,
                       state, debt_units, version
                  from pep_metering.quota_account
                 where scope_kind = ? and subject_id = ?
                 order by scope_kind, subject_id, id
                   for update
                """, (rs, rowNum) -> account(rs), scopeKind, subjectId);
    }

    public List<QuotaRecords.Account> accounts(String scopeKind, String subjectId) {
        return jdbc.query("""
                select id, scope_kind, subject_id, policy_key, active_revision, current_bucket_id,
                       state, debt_units, version
                  from pep_metering.quota_account
                 where scope_kind = ? and subject_id = ?
                 order by policy_key
                """, (rs, rowNum) -> account(rs), scopeKind, subjectId);
    }

    public Optional<QuotaRecords.Account> findAccount(UUID id) {
        return jdbc.query("""
                        select id, scope_kind, subject_id, policy_key, active_revision, current_bucket_id,
                               state, debt_units, version
                          from pep_metering.quota_account
                         where id = ?
                        """, (rs, rowNum) -> account(rs), id).stream().findFirst();
    }

    public Optional<QuotaRecords.Account> lockAccount(UUID id) {
        return jdbc.query("""
                        select id, scope_kind, subject_id, policy_key, active_revision, current_bucket_id,
                               state, debt_units, version
                          from pep_metering.quota_account
                         where id = ?
                           for update
                        """, (rs, rowNum) -> account(rs), id).stream().findFirst();
    }

    public Optional<QuotaRecords.Policy> findPolicy(String policyKey, long revision) {
        return jdbc
                .query(
                        """
                        select id, policy_key, revision, mode, limit_units, duration_seconds,
                               effective_at, expires_at
                          from pep_metering.quota_policy_version
                         where policy_key = ? and revision = ?
                        """,
                        (rs, rowNum) -> new QuotaRecords.Policy(
                                rs.getObject("id", UUID.class),
                                rs.getString("policy_key"),
                                rs.getLong("revision"),
                                rs.getString("mode"),
                                rs.getLong("limit_units"),
                                (Long) rs.getObject("duration_seconds"),
                                rs.getObject("effective_at", OffsetDateTime.class),
                                rs.getObject("expires_at", OffsetDateTime.class)),
                        policyKey,
                        revision)
                .stream()
                .findFirst();
    }

    public Optional<QuotaRecords.Bucket> findBucket(UUID id) {
        return findBucket(id, false);
    }

    public Optional<QuotaRecords.Bucket> lockBucket(UUID id) {
        return findBucket(id, true);
    }

    private Optional<QuotaRecords.Bucket> findBucket(UUID id, boolean lock) {
        String sql = """
                select id, account_id, policy_revision, generation, start_at, end_at, activation_state,
                       limit_units, used_units, held_units
                  from pep_metering.quota_bucket
                 where id = ?
                """ + (lock ? " for update" : "");
        return jdbc.query(sql, (rs, rowNum) -> bucket(rs), id).stream().findFirst();
    }

    public long nextGeneration(UUID accountId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(generation), -1) + 1 from pep_metering.quota_bucket where account_id = ?",
                Long.class,
                accountId);
        return value == null ? 0L : value;
    }

    public UUID insertBucket(
            UUID accountId,
            long policyRevision,
            long generation,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String activationState,
            long limitUnits) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into pep_metering.quota_bucket
                    (id, account_id, policy_revision, generation, start_at, end_at,
                     activation_state, limit_units, used_units, held_units)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, id, accountId, policyRevision, generation, startAt, endAt, activationState, limitUnits);
        return id;
    }

    public void setCurrentBucket(UUID accountId, UUID bucketId, long expectedVersion) {
        int updated = jdbc.update("""
                update pep_metering.quota_account
                   set current_bucket_id = ?, version = version + 1, updated_at = now()
                 where id = ? and version = ?
                """, bucketId, accountId, expectedVersion);
        if (updated == 0) {
            throw new IllegalStateException("额度账户并发更新失败");
        }
    }

    public void updateBucketState(UUID bucketId, String state) {
        jdbc.update("update pep_metering.quota_bucket set activation_state = ? where id = ?", state, bucketId);
    }

    public void updateBucketPolicy(UUID bucketId, long limitUnits, long policyRevision) {
        jdbc.update("""
                update pep_metering.quota_bucket
                   set limit_units = ?, policy_revision = ?
                 where id = ?
                """, limitUnits, policyRevision, bucketId);
    }

    public void addHeld(UUID bucketId, long delta) {
        jdbc.update("update pep_metering.quota_bucket set held_units = held_units + ? where id = ?", delta, bucketId);
    }

    public void settleBucket(UUID bucketId, long releasedHeld, long settledUnits) {
        jdbc.update("""
                update pep_metering.quota_bucket
                   set held_units = greatest(0, held_units - ?),
                       used_units = used_units + ?
                 where id = ?
                """, releasedHeld, settledUnits, bucketId);
    }

    public void settleReleasedBucket(UUID bucketId, long settledUnits) {
        jdbc.update(
                "update pep_metering.quota_bucket set used_units = used_units + ? where id = ?",
                settledUnits,
                bucketId);
    }

    public Optional<QuotaRecords.Reservation> findReservationByCall(UUID modelCallId) {
        return jdbc
                .query(
                        """
                        select id, model_call_id, reserved_units, state
                          from pep_metering.quota_reservation
                         where model_call_id = ?
                        """,
                        (rs, rowNum) -> new QuotaRecords.Reservation(
                                rs.getObject("id", UUID.class),
                                rs.getObject("model_call_id", UUID.class),
                                rs.getLong("reserved_units"),
                                rs.getString("state")),
                        modelCallId)
                .stream()
                .findFirst();
    }

    public Optional<QuotaRecords.Reservation> lockReservation(UUID id) {
        return jdbc
                .query(
                        """
                        select id, model_call_id, reserved_units, state
                          from pep_metering.quota_reservation
                         where id = ?
                           for update
                        """,
                        (rs, rowNum) -> new QuotaRecords.Reservation(
                                rs.getObject("id", UUID.class),
                                rs.getObject("model_call_id", UUID.class),
                                rs.getLong("reserved_units"),
                                rs.getString("state")),
                        id)
                .stream()
                .findFirst();
    }

    public List<QuotaRecords.Charge> lockCharges(UUID reservationId) {
        return jdbc.query(
                """
                select id, reservation_id, account_id, bucket_id, reserved_units, settled_units, state
                  from pep_metering.reservation_charge
                 where reservation_id = ?
                 order by account_id
                   for update
                """,
                (rs, rowNum) -> new QuotaRecords.Charge(
                        rs.getObject("id", UUID.class),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("bucket_id", UUID.class),
                        rs.getLong("reserved_units"),
                        (Long) rs.getObject("settled_units"),
                        rs.getString("state")),
                reservationId);
    }

    public Optional<QuotaRecords.Call> findCall(UUID logicalCallId, int attemptNo) {
        return jdbc.query("""
                        select id, logical_call_id, attempt_no, billing_subject_id, scope_kind, actor_id,
                               tenant_id, project_id, task_id, run_id, model_profile_key, provider, model,
                               request_hash, state
                          from pep_metering.model_call
                         where logical_call_id = ? and attempt_no = ?
                        """, (rs, rowNum) -> call(rs), logicalCallId, attemptNo).stream()
                .findFirst();
    }

    public Optional<QuotaRecords.Call> findCallById(UUID id) {
        return jdbc.query("""
                        select id, logical_call_id, attempt_no, billing_subject_id, scope_kind, actor_id,
                               tenant_id, project_id, task_id, run_id, model_profile_key, provider, model,
                               request_hash, state
                          from pep_metering.model_call
                         where id = ?
                        """, (rs, rowNum) -> call(rs), id).stream().findFirst();
    }

    public void insertCall(QuotaRecords.Call call) {
        jdbc.update(
                """
                insert into pep_metering.model_call
                    (id, logical_call_id, attempt_no, billing_subject_id, scope_kind, actor_id, tenant_id,
                     project_id, task_id, run_id, model_profile_key, provider, model, request_hash, state)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                call.id(),
                call.logicalCallId(),
                call.attemptNo(),
                call.billingSubjectId(),
                call.scopeKind(),
                call.actorId(),
                call.tenantId(),
                call.projectId(),
                call.taskId(),
                call.runId(),
                call.modelProfileKey(),
                call.provider(),
                call.model(),
                call.requestHash(),
                call.state());
    }

    public void insertReservation(UUID id, UUID modelCallId, long units, OffsetDateTime dispatchDeadline) {
        jdbc.update("""
                insert into pep_metering.quota_reservation
                    (id, model_call_id, reserved_units, state, dispatch_deadline, version)
                values (?, ?, ?, 'HELD', ?, 0)
                """, id, modelCallId, units, dispatchDeadline);
    }

    public void insertCharge(UUID id, UUID reservationId, UUID accountId, UUID bucketId, long units) {
        jdbc.update("""
                insert into pep_metering.reservation_charge
                    (id, reservation_id, account_id, bucket_id, reserved_units, state)
                values (?, ?, ?, ?, ?, 'HELD')
                """, id, reservationId, accountId, bucketId, units);
    }

    public void updateReservationState(UUID id, String state) {
        jdbc.update("""
                update pep_metering.quota_reservation
                   set state = ?, version = version + 1, updated_at = now()
                 where id = ?
                """, state, id);
    }

    public boolean markReservationDispatched(UUID id) {
        return jdbc.update("""
                update pep_metering.quota_reservation
                   set state = 'DISPATCHED',
                       dispatched_at = now(),
                       version = version + 1,
                       updated_at = now()
                 where id = ?
                   and state = 'HELD'
                   and dispatch_deadline >= now()
                """, id) == 1;
    }

    public void updateChargeState(UUID id, String state, Long settledUnits) {
        jdbc.update("""
                update pep_metering.reservation_charge
                   set state = ?, settled_units = ?
                 where id = ?
                """, state, settledUnits, id);
    }

    public void updateCallState(UUID id, String state, String providerRequestId) {
        jdbc.update("""
                update pep_metering.model_call
                   set state = ?, provider_request_id = coalesce(?, provider_request_id), updated_at = now()
                 where id = ?
                """, state, providerRequestId, id);
    }

    public void markCallDispatched(UUID id) {
        jdbc.update("""
                update pep_metering.model_call
                   set state = 'DISPATCHED',
                       dispatched_at = now(),
                       updated_at = now()
                 where id = ?
                """, id);
    }

    public void appendLedger(
            UUID accountId,
            UUID bucketId,
            UUID reservationId,
            String eventType,
            long deltaUsed,
            long deltaHeld,
            String sourceEventId) {
        jdbc.update(
                """
                insert into pep_metering.quota_ledger_entry
                    (id, account_id, bucket_id, reservation_id, event_type, delta_used, delta_held, source_event_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (account_id, source_event_id) do nothing
                """,
                UUID.randomUUID(),
                accountId,
                bucketId,
                reservationId,
                eventType,
                deltaUsed,
                deltaHeld,
                sourceEventId);
    }

    public void insertUsage(
            UUID modelCallId,
            top.yealike.pep.model.contract.ModelUsage usage,
            String providerUsageHash,
            String rawUsage) {
        jdbc.update(
                """
                insert into pep_metering.model_usage
                    (id, model_call_id, normalization_version, input_tokens, output_tokens,
                     cache_read_tokens, cache_write_tokens, reasoning_tokens, quota_units,
                     quality, provider_usage_hash, raw_usage)
                values (?, ?, 'v1', ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                on conflict (model_call_id) do nothing
                """,
                UUID.randomUUID(),
                modelCallId,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                usage.quotaUnits(),
                usage.quality(),
                providerUsageHash,
                rawUsage);
    }

    public void markDebt(UUID accountId, long debtUnits) {
        jdbc.update("""
                update pep_metering.quota_account
                   set state = 'DEBT', debt_units = greatest(debt_units, ?), updated_at = now()
                 where id = ?
                """, debtUnits, accountId);
    }

    public void updateAccountState(UUID accountId, String state) {
        jdbc.update("""
                update pep_metering.quota_account
                   set state = ?, updated_at = now(), version = version + 1
                 where id = ?
                """, state, accountId);
    }

    public List<QuotaRecords.Reservation> expiredHeldReservations(int limit) {
        return jdbc.query(
                """
                select id, model_call_id, reserved_units, state
                  from pep_metering.quota_reservation
                 where state = 'HELD' and dispatch_deadline < now()
                 order by dispatch_deadline
                 limit ?
                   for update skip locked
                """,
                (rs, rowNum) -> new QuotaRecords.Reservation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("model_call_id", UUID.class),
                        rs.getLong("reserved_units"),
                        rs.getString("state")),
                limit);
    }

    public List<QuotaRecords.Reservation> expiredDispatchedReservations(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                select id, model_call_id, reserved_units, state
                  from pep_metering.quota_reservation
                 where state = 'DISPATCHED' and created_at < ?
                 order by created_at
                 limit ?
                   for update skip locked
                """,
                (rs, rowNum) -> new QuotaRecords.Reservation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("model_call_id", UUID.class),
                        rs.getLong("reserved_units"),
                        rs.getString("state")),
                cutoff,
                limit);
    }

    public long pendingReconciliation(String scopeKind, String subjectId) {
        Long value = jdbc.queryForObject("""
                select count(*)
                  from pep_metering.quota_reservation r
                  join pep_metering.model_call c on c.id = r.model_call_id
                 where c.scope_kind = ? and c.billing_subject_id = ?
                   and r.state = 'UNCERTAIN'
                """, Long.class, scopeKind, subjectId);
        return value == null ? 0 : value;
    }

    public List<QuotaRecords.Charge> chargesForCall(UUID modelCallId) {
        return jdbc.query(
                """
                select c.id, c.reservation_id, c.account_id, c.bucket_id, c.reserved_units,
                       c.settled_units, c.state
                  from pep_metering.reservation_charge c
                  join pep_metering.quota_reservation r on r.id = c.reservation_id
                 where r.model_call_id = ?
                 order by c.account_id
                """,
                (rs, rowNum) -> new QuotaRecords.Charge(
                        rs.getObject("id", UUID.class),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("bucket_id", UUID.class),
                        rs.getLong("reserved_units"),
                        (Long) rs.getObject("settled_units"),
                        rs.getString("state")),
                modelCallId);
    }

    public int nextAdjustmentNo(UUID modelCallId) {
        Integer value = jdbc.queryForObject(
                "select coalesce(max(adjustment_no), 0) + 1 from pep_metering.usage_adjustment where model_call_id = ?",
                Integer.class,
                modelCallId);
        return value == null ? 1 : value;
    }

    public void adjustBucketUsed(UUID bucketId, long delta) {
        jdbc.update(
                "update pep_metering.quota_bucket set used_units = greatest(0, used_units + ?) where id = ?",
                delta,
                bucketId);
    }

    public List<java.util.Map<String, Object>> usageRows(
            String scopeKind, String subjectId, int limit, String modelKey, String projectId) {
        StringBuilder sql = new StringBuilder("""
                select c.id, c.logical_call_id, c.attempt_no, c.model_profile_key, c.provider, c.model,
                       c.project_id, c.run_id, c.state, c.created_at,
                       u.input_tokens, u.output_tokens, u.quota_units, u.quality
                  from pep_metering.model_call c
                  left join pep_metering.model_usage u on u.model_call_id = c.id
                 where c.scope_kind = ? and c.billing_subject_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(scopeKind);
        args.add(subjectId);
        if (modelKey != null && !modelKey.isBlank()) {
            sql.append(" and c.model_profile_key = ?");
            args.add(modelKey);
        }
        if (projectId != null && !projectId.isBlank()) {
            sql.append(" and c.project_id = ?");
            args.add(projectId);
        }
        sql.append(" order by c.created_at desc, c.id desc limit ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("callId", rs.getString("id"));
                    row.put("logicalCallId", rs.getString("logical_call_id"));
                    row.put("attemptNo", rs.getInt("attempt_no"));
                    row.put("model", rs.getString("model_profile_key"));
                    row.put("provider", rs.getString("provider"));
                    row.put("projectId", rs.getString("project_id"));
                    row.put("runId", rs.getString("run_id"));
                    row.put("state", rs.getString("state"));
                    row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    row.put("inputTokens", rs.getObject("input_tokens"));
                    row.put("outputTokens", rs.getObject("output_tokens"));
                    row.put("quotaUnits", rs.getObject("quota_units"));
                    row.put("quality", rs.getString("quality"));
                    return row;
                },
                args.toArray());
    }

    public Integer maxPolicyRevision(String policyKey) {
        return jdbc.queryForObject(
                "select coalesce(max(revision), 0)::int from pep_metering.quota_policy_version where policy_key = ?",
                Integer.class,
                policyKey);
    }

    public void insertPolicy(
            UUID id,
            String policyKey,
            long revision,
            String mode,
            long limitUnits,
            Long durationSeconds,
            OffsetDateTime effectiveAt,
            OffsetDateTime expiresAt) {
        jdbc.update("""
                insert into pep_metering.quota_policy_version
                    (id, policy_key, revision, mode, limit_units, duration_seconds, effective_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, policyKey, revision, mode, limitUnits, durationSeconds, effectiveAt, expiresAt);
    }

    public void insertAccount(UUID id, String scopeKind, String subjectId, String policyKey, long revision) {
        jdbc.update("""
                insert into pep_metering.quota_account
                    (id, scope_kind, subject_id, policy_key, active_revision, state, debt_units, version)
                values (?, ?, ?, ?, ?, 'ACTIVE', 0, 0)
                on conflict (scope_kind, subject_id, policy_key) do nothing
                """, id, scopeKind, subjectId, policyKey, revision);
    }

    public void updateAccountRevision(UUID accountId, long revision) {
        jdbc.update("""
                update pep_metering.quota_account
                   set active_revision = ?, updated_at = now(), version = version + 1
                 where id = ?
                """, revision, accountId);
    }

    public void insertChangeRequest(
            UUID accountId,
            Long oldRevision,
            Long newRevision,
            String action,
            String effectiveMode,
            String reason,
            String actorId,
            String idempotencyKey) {
        jdbc.update(
                """
                insert into pep_metering.quota_change_request
                    (id, account_id, old_revision, new_revision, action, effective_mode, reason,
                     actor_id, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                accountId,
                oldRevision,
                newRevision,
                action,
                effectiveMode,
                reason,
                actorId,
                idempotencyKey);
    }

    public Optional<QuotaRecords.ChangeRequest> findChangeRequest(String actorId, String idempotencyKey) {
        return jdbc
                .query(
                        """
                        select account_id, new_revision, action, effective_mode, reason
                          from pep_metering.quota_change_request
                         where actor_id = ? and idempotency_key = ?
                        """,
                        (rs, rowNum) -> new QuotaRecords.ChangeRequest(
                                rs.getObject("account_id", UUID.class),
                                (Long) rs.getObject("new_revision"),
                                rs.getString("action"),
                                rs.getString("effective_mode"),
                                rs.getString("reason")),
                        actorId,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    public void insertAdjustment(
            UUID modelCallId,
            int adjustmentNo,
            long deltaUnits,
            String reason,
            String evidenceRef,
            String operatorId,
            String idempotencyKey) {
        jdbc.update(
                """
                insert into pep_metering.usage_adjustment
                    (id, model_call_id, adjustment_no, delta_units, reason, evidence_ref,
                     operator_id, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                modelCallId,
                adjustmentNo,
                deltaUnits,
                reason,
                evidenceRef,
                operatorId,
                idempotencyKey);
    }

    private static QuotaRecords.Account account(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QuotaRecords.Account(
                rs.getObject("id", UUID.class),
                rs.getString("scope_kind"),
                rs.getString("subject_id"),
                rs.getString("policy_key"),
                rs.getLong("active_revision"),
                rs.getObject("current_bucket_id", UUID.class),
                rs.getString("state"),
                rs.getLong("debt_units"),
                rs.getLong("version"));
    }

    private static QuotaRecords.Bucket bucket(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QuotaRecords.Bucket(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getLong("policy_revision"),
                rs.getLong("generation"),
                rs.getObject("start_at", OffsetDateTime.class),
                rs.getObject("end_at", OffsetDateTime.class),
                rs.getString("activation_state"),
                rs.getLong("limit_units"),
                rs.getLong("used_units"),
                rs.getLong("held_units"));
    }

    private static QuotaRecords.Call call(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QuotaRecords.Call(
                rs.getObject("id", UUID.class),
                rs.getObject("logical_call_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getString("billing_subject_id"),
                rs.getString("scope_kind"),
                rs.getString("actor_id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("task_id"),
                rs.getString("run_id"),
                rs.getString("model_profile_key"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("request_hash"),
                rs.getString("state"));
    }
}
