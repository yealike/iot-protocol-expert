package top.yealike.pep.modelgateway.quota;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.model.contract.ModelErrorCodes;
import top.yealike.pep.model.contract.ModelUsage;
import top.yealike.pep.modelgateway.config.GatewayProperties;
import top.yealike.pep.modelgateway.support.ApiException;

/**
 * 配额准入与结算。
 *
 * <p>所有额度账户按 (scope_kind, subject_id, account_id) 稳定顺序加行锁，检查与冻结在同一事务完成；
 * 账本只追加，used/held 计数与账本同事务提交。窗口采用懒启动固定时长语义。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final QuotaRepository repository;
    private final TransactionTemplate transactions;
    private final GatewayProperties properties;

    public QuotaService(QuotaRepository repository, TransactionTemplate transactions, GatewayProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
    }

    public record ReservationRequest(
            UUID logicalCallId,
            int attemptNo,
            String requestHash,
            String scopeKind,
            String subjectId,
            String actorId,
            String tenantId,
            String projectId,
            String taskId,
            String runId,
            String modelProfileKey,
            String provider,
            String model,
            long reservedUnits) {}

    public record ReservationHandle(
            UUID reservationId, UUID callId, long reservedUnits, List<BucketView> buckets, boolean replayed) {}

    public record BucketView(
            UUID accountId,
            String policyKey,
            String mode,
            long limitUnits,
            long usedUnits,
            long heldUnits,
            long remainingUnits,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String activationState) {}

    public ReservationHandle reserve(ReservationRequest request) {
        if (request.reservedUnits() <= 0) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "预占额度必须大于 0");
        }
        return transactions.execute(status -> doReserve(request));
    }

    private ReservationHandle doReserve(ReservationRequest request) {
        var existing = repository.findCall(request.logicalCallId(), request.attemptNo());
        if (existing.isPresent()) {
            QuotaRecords.Call call = existing.get();
            if (!call.requestHash().equals(request.requestHash())) {
                throw ApiException.conflict(ModelErrorCodes.CALL_CONFLICT, "同一调用 ID 的请求内容不一致");
            }
            var reservation = repository.findReservationByCall(call.id()).orElseThrow();
            return new ReservationHandle(reservation.id(), call.id(), reservation.reservedUnits(), List.of(), true);
        }

        OffsetDateTime now = repository.now();
        List<QuotaRecords.Account> accounts = repository.lockAccounts(request.scopeKind(), request.subjectId());
        if (accounts.isEmpty()) {
            throw new ApiException(429, ModelErrorCodes.QUOTA_EXHAUSTED, "账号未配置模型额度，请联系管理员分配");
        }

        List<BucketView> views = new ArrayList<>();
        List<BucketAndAccount> targets = new ArrayList<>();
        for (QuotaRecords.Account account : accounts) {
            if (!"ACTIVE".equals(account.state())) {
                throw new ApiException(
                        429, ModelErrorCodes.QUOTA_EXHAUSTED, "额度账户状态为 " + account.state() + "，请先处理欠额或停用原因");
            }
            QuotaRecords.Policy policy = repository
                    .findPolicy(account.policyKey(), account.activeRevision())
                    .orElseThrow(() -> ApiException.unavailable(ModelErrorCodes.QUOTA_UNAVAILABLE, "额度策略版本不存在"));
            if (policy.effectiveAt().isAfter(now)
                    || (policy.expiresAt() != null && !policy.expiresAt().isAfter(now))) {
                throw new ApiException(429, ModelErrorCodes.QUOTA_TOTAL_EXHAUSTED, "额度策略不在有效期内");
            }
            QuotaRecords.Bucket bucket = ensureBucket(account, policy, now);
            if (bucket.remaining() < request.reservedUnits()) {
                String code = "TOTAL".equals(policy.mode())
                        ? ModelErrorCodes.QUOTA_TOTAL_EXHAUSTED
                        : ModelErrorCodes.QUOTA_EXHAUSTED;
                String retry = bucket.endAt() == null
                        ? "总量额度已耗尽"
                        : "当前窗口额度不足，将于 " + bucket.endAt().withOffsetSameInstant(ZoneOffset.UTC) + " 重置";
                throw new ApiException(429, code, retry + "（剩余 " + bucket.remaining() + " 单位）");
            }
            targets.add(new BucketAndAccount(account, policy, bucket));
            views.add(view(account, policy, bucket));
        }

        UUID callId = UUID.randomUUID();
        repository.insertCall(new QuotaRecords.Call(
                callId,
                request.logicalCallId(),
                request.attemptNo(),
                request.subjectId(),
                request.scopeKind(),
                request.actorId(),
                request.tenantId(),
                request.projectId(),
                request.taskId(),
                request.runId(),
                request.modelProfileKey(),
                request.provider(),
                request.model(),
                request.requestHash(),
                "HELD"));

        UUID reservationId = UUID.randomUUID();
        OffsetDateTime deadline = now.plusSeconds(properties.getDispatchDeadlineSeconds());
        repository.insertReservation(reservationId, callId, request.reservedUnits(), deadline);
        for (BucketAndAccount target : targets) {
            UUID chargeId = UUID.randomUUID();
            repository.insertCharge(
                    chargeId,
                    reservationId,
                    target.account().id(),
                    target.bucket().id(),
                    request.reservedUnits());
            repository.addHeld(target.bucket().id(), request.reservedUnits());
            repository.appendLedger(
                    target.account().id(),
                    target.bucket().id(),
                    reservationId,
                    "HELD",
                    0,
                    request.reservedUnits(),
                    "HELD:" + chargeId);
        }
        log.info(
                "quota reserved: subject={} scope={} units={} reservation={}",
                request.subjectId(),
                request.scopeKind(),
                request.reservedUnits(),
                reservationId);
        return new ReservationHandle(reservationId, callId, request.reservedUnits(), views, false);
    }

    private QuotaRecords.Bucket ensureBucket(
            QuotaRecords.Account account, QuotaRecords.Policy policy, OffsetDateTime now) {
        QuotaRecords.Bucket current = account.currentBucketId() == null
                ? null
                : repository.findBucket(account.currentBucketId()).orElse(null);
        if ("TOTAL".equals(policy.mode())) {
            if (current == null) {
                return createBucket(account, policy, now, "TOTAL", "ACTIVE", null);
            }
            if (current.limitUnits() != policy.limitUnits()) {
                repository.updateBucketLimit(current.id(), policy.limitUnits());
                current = repository.findBucket(current.id()).orElseThrow();
            }
            return current;
        }
        boolean expired = current != null
                && (current.endAt() == null
                        || !current.endAt().isAfter(now)
                        || "VOID".equals(current.activationState())
                        || "EXPIRED".equals(current.activationState()));
        if (current == null || expired) {
            if (current != null && "PROVISIONAL".equals(current.activationState())) {
                repository.updateBucketState(current.id(), "EXPIRED");
            }
            return createBucket(account, policy, now, "WINDOW", "PROVISIONAL", policy.durationSeconds());
        }
        return current;
    }

    private QuotaRecords.Bucket createBucket(
            QuotaRecords.Account account,
            QuotaRecords.Policy policy,
            OffsetDateTime now,
            String kind,
            String state,
            Long durationSeconds) {
        long generation = repository.nextGeneration(account.id());
        OffsetDateTime end = durationSeconds == null ? null : now.plusSeconds(durationSeconds);
        UUID bucketId = repository.insertBucket(
                account.id(), policy.revision(), generation, now, end, state, policy.limitUnits());
        repository.setCurrentBucket(account.id(), bucketId, account.version());
        return repository.findBucket(bucketId).orElseThrow();
    }

    public void markDispatched(UUID reservationId) {
        transactions.executeWithoutResult(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if (!"HELD".equals(reservation.state())) {
                return;
            }
            repository.updateReservationState(reservationId, "DISPATCHED");
            repository.updateCallState(reservation.modelCallId(), "DISPATCHED", null);
            for (QuotaRecords.Charge charge : repository.lockCharges(reservationId)) {
                repository.updateChargeState(charge.id(), "DISPATCHED", null);
                QuotaRecords.Bucket bucket =
                        repository.lockBucket(charge.bucketId()).orElseThrow();
                if ("PROVISIONAL".equals(bucket.activationState())) {
                    repository.updateBucketState(bucket.id(), "ACTIVE");
                }
            }
        });
    }

    public void settle(UUID reservationId, ModelUsage usage, String providerRequestId, String rawUsage) {
        transactions.executeWithoutResult(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if ("SETTLED".equals(reservation.state())) {
                return;
            }
            if ("RELEASED".equals(reservation.state())) {
                log.warn("usage arrived after release: reservation={} units={}", reservationId, usage.quotaUnits());
                repository.updateCallState(reservation.modelCallId(), "UNCERTAIN", providerRequestId);
                return;
            }
            long settled = usage.quotaUnits();
            for (QuotaRecords.Charge charge : repository.lockCharges(reservationId)) {
                repository.updateChargeState(charge.id(), "SETTLED", settled);
                repository.settleBucket(charge.bucketId(), charge.reservedUnits(), settled);
                repository.appendLedger(
                        charge.accountId(),
                        charge.bucketId(),
                        reservationId,
                        "SETTLED",
                        settled,
                        -charge.reservedUnits(),
                        "SETTLED:" + charge.id());
                QuotaRecords.Bucket bucket =
                        repository.lockBucket(charge.bucketId()).orElseThrow();
                if (bucket.usedUnits() + bucket.heldUnits() > bucket.limitUnits()) {
                    repository.markDebt(
                            charge.accountId(), bucket.usedUnits() + bucket.heldUnits() - bucket.limitUnits());
                }
            }
            repository.insertUsage(reservation.modelCallId(), usage, null, rawUsage);
            repository.updateReservationState(reservationId, "SETTLED");
            repository.updateCallState(reservation.modelCallId(), "SETTLED", providerRequestId);
        });
    }

    /** 仅在确定未派发时释放；已派发或不确定的调用必须对账，不能直接退款。 */
    public void release(UUID reservationId) {
        release(reservationId, false);
    }

    /** 供应商明确返回校验失败、确认没有计量时，允许释放已派发但未产生用量的预占。 */
    public void releaseRejected(UUID reservationId) {
        release(reservationId, true);
    }

    private void release(UUID reservationId, boolean allowDispatched) {
        transactions.executeWithoutResult(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if ("RELEASED".equals(reservation.state()) || "SETTLED".equals(reservation.state())) {
                return;
            }
            if (!"HELD".equals(reservation.state()) && !(allowDispatched && "DISPATCHED".equals(reservation.state()))) {
                throw ApiException.conflict(ModelErrorCodes.QUOTA_PENDING_RECONCILIATION, "调用已派发，不能直接释放，请走对账");
            }
            for (QuotaRecords.Charge charge : repository.lockCharges(reservationId)) {
                repository.updateChargeState(charge.id(), "RELEASED", null);
                repository.addHeld(charge.bucketId(), -charge.reservedUnits());
                repository.appendLedger(
                        charge.accountId(),
                        charge.bucketId(),
                        reservationId,
                        "RELEASED",
                        0,
                        -charge.reservedUnits(),
                        "RELEASED:" + charge.id());
                voidProvisional(charge.accountId(), charge.bucketId());
            }
            repository.updateReservationState(reservationId, "RELEASED");
            repository.updateCallState(reservation.modelCallId(), "RELEASED", null);
        });
    }

    public void markUncertain(UUID reservationId, String providerRequestId) {
        transactions.executeWithoutResult(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if ("SETTLED".equals(reservation.state()) || "RELEASED".equals(reservation.state())) {
                return;
            }
            repository.updateReservationState(reservationId, "UNCERTAIN");
            repository.updateCallState(reservation.modelCallId(), "UNCERTAIN", providerRequestId);
            for (QuotaRecords.Charge charge : repository.lockCharges(reservationId)) {
                repository.updateChargeState(charge.id(), "UNCERTAIN", null);
            }
        });
    }

    public void reconcileRelease(UUID reservationId) {
        transactions.executeWithoutResult(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if (!"UNCERTAIN".equals(reservation.state())) {
                throw ApiException.conflict(ModelErrorCodes.CALL_CONFLICT, "只有不确定调用可以对账");
            }
            for (QuotaRecords.Charge charge : repository.lockCharges(reservationId)) {
                repository.updateChargeState(charge.id(), "RELEASED", null);
                repository.addHeld(charge.bucketId(), -charge.reservedUnits());
                repository.appendLedger(
                        charge.accountId(),
                        charge.bucketId(),
                        reservationId,
                        "RECONCILED",
                        0,
                        -charge.reservedUnits(),
                        "RECONCILED:" + charge.id());
            }
            repository.updateReservationState(reservationId, "RELEASED");
            repository.updateCallState(reservation.modelCallId(), "RELEASED", null);
        });
    }

    private void voidProvisional(UUID accountId, UUID bucketId) {
        QuotaRecords.Bucket bucket = repository.lockBucket(bucketId).orElseThrow();
        if (!"PROVISIONAL".equals(bucket.activationState())) {
            return;
        }
        if (bucket.usedUnits() > 0 || bucket.heldUnits() > 0) {
            return;
        }
        repository.updateBucketState(bucketId, "VOID");
        QuotaRecords.Account account = repository.findAccount(accountId).orElseThrow();
        if (bucketId.equals(account.currentBucketId())) {
            repository.setCurrentBucket(accountId, null, account.version());
        }
    }

    @Scheduled(fixedDelayString = "${pep.gateway.sweep-interval-ms:30000}")
    public void expireHeldReservations() {
        transactions.executeWithoutResult(status -> {
            for (QuotaRecords.Reservation reservation : repository.expiredHeldReservations(50)) {
                for (QuotaRecords.Charge charge : repository.lockCharges(reservation.id())) {
                    repository.updateChargeState(charge.id(), "RELEASED", null);
                    repository.addHeld(charge.bucketId(), -charge.reservedUnits());
                    repository.appendLedger(
                            charge.accountId(),
                            charge.bucketId(),
                            reservation.id(),
                            "EXPIRED",
                            0,
                            -charge.reservedUnits(),
                            "EXPIRED:" + charge.id());
                    voidProvisional(charge.accountId(), charge.bucketId());
                }
                repository.updateReservationState(reservation.id(), "RELEASED");
                repository.updateCallState(reservation.modelCallId(), "RELEASED", null);
            }
        });
    }

    // ---------------------------------------------------------------- 管理

    public record GrantRequest(
            String scopeKind,
            String subjectId,
            String mode,
            long limitUnits,
            Long durationSeconds,
            String policyKey,
            String reason,
            String actorId,
            String idempotencyKey) {}

    public Map<String, Object> grant(GrantRequest request) {
        String mode = request.mode() == null ? "" : request.mode().toUpperCase(java.util.Locale.ROOT);
        if (!"TOTAL".equals(mode) && !"ON_FIRST_USE_WINDOW".equals(mode)) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "额度模式必须是 TOTAL 或 ON_FIRST_USE_WINDOW");
        }
        if (request.limitUnits() <= 0) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "额度必须大于 0");
        }
        if ("ON_FIRST_USE_WINDOW".equals(mode)
                && (request.durationSeconds() == null || request.durationSeconds() <= 0)) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "窗口模式必须指定正数窗口秒数");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "缺少 Idempotency-Key");
        }
        return transactions.execute(status -> {
            var replay = repository.findChangeRequest(request.actorId(), request.idempotencyKey());
            if (replay.isPresent()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("accountId", replay.get().accountId().toString());
                body.put("revision", replay.get().newRevision());
                body.put("mode", replay.get().effectiveMode());
                body.put("replayed", true);
                return body;
            }
            String policyKey = request.policyKey() == null
                            || request.policyKey().isBlank()
                    ? request.scopeKind().toLowerCase(java.util.Locale.ROOT) + ":" + request.subjectId() + ":" + mode
                    : request.policyKey();
            int max = repository.maxPolicyRevision(policyKey);
            long revision = max + 1L;
            OffsetDateTime now = repository.now();
            repository.insertPolicy(
                    UUID.randomUUID(),
                    policyKey,
                    revision,
                    mode,
                    request.limitUnits(),
                    "ON_FIRST_USE_WINDOW".equals(mode) ? request.durationSeconds() : null,
                    now,
                    null);
            UUID accountId = UUID.randomUUID();
            repository.insertAccount(accountId, request.scopeKind(), request.subjectId(), policyKey, revision);
            QuotaRecords.Account account = repository.accounts(request.scopeKind(), request.subjectId()).stream()
                    .filter(value -> value.policyKey().equals(policyKey))
                    .findFirst()
                    .orElseThrow();
            long oldRevision = account.activeRevision();
            if (oldRevision != revision) {
                repository.updateAccountRevision(account.id(), revision);
            }
            repository.insertChangeRequest(
                    account.id(),
                    oldRevision,
                    revision,
                    "GRANT",
                    mode,
                    request.reason() == null ? "" : request.reason(),
                    request.actorId(),
                    request.idempotencyKey());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accountId", account.id().toString());
            body.put("policyKey", policyKey);
            body.put("revision", revision);
            body.put("mode", mode);
            return body;
        });
    }

    public Map<String, Object> suspend(UUID accountId, String state, String actorId, String reason) {
        transactions.executeWithoutResult(status -> {
            repository.lockAccount(accountId).orElseThrow(() -> ApiException.notFound("额度账户不存在"));
            repository.updateAccountState(accountId, state);
            repository.insertChangeRequest(
                    accountId,
                    null,
                    null,
                    "SUSPEND",
                    state,
                    reason,
                    actorId,
                    UUID.randomUUID().toString());
        });
        return Map.of("accountId", accountId.toString(), "state", state);
    }

    public Map<String, Object> adjust(UUID modelCallId, long deltaUnits, String reason, String operator, String key) {
        return transactions.execute(status -> {
            var call = repository.findCallById(modelCallId).orElseThrow(() -> ApiException.notFound("模型调用不存在"));
            int adjustmentNo = repository.nextAdjustmentNo(modelCallId);
            repository.insertAdjustment(modelCallId, adjustmentNo, deltaUnits, reason, null, operator, key);
            for (QuotaRecords.Charge charge : repository.chargesForCall(modelCallId)) {
                repository.adjustBucketUsed(charge.bucketId(), deltaUnits);
                repository.appendLedger(
                        charge.accountId(),
                        charge.bucketId(),
                        charge.reservationId(),
                        "ADJUSTMENT",
                        deltaUnits,
                        0,
                        "ADJUSTMENT:" + key);
            }
            return Map.of("modelCallId", call.id().toString(), "adjustmentNo", adjustmentNo, "deltaUnits", deltaUnits);
        });
    }

    public Map<String, Object> snapshot(String scopeKind, String subjectId) {
        List<QuotaRecords.Account> accounts = repository.accounts(scopeKind, subjectId);
        List<Map<String, Object>> policies = new ArrayList<>();
        for (QuotaRecords.Account account : accounts) {
            QuotaRecords.Policy policy = repository
                    .findPolicy(account.policyKey(), account.activeRevision())
                    .orElse(null);
            if (policy == null) {
                continue;
            }
            QuotaRecords.Bucket bucket = account.currentBucketId() == null
                    ? null
                    : repository.findBucket(account.currentBucketId()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountId", account.id().toString());
            row.put("policyKey", account.policyKey());
            row.put("mode", policy.mode());
            row.put("state", account.state());
            row.put("limitUnits", policy.limitUnits());
            row.put("usedUnits", bucket == null ? 0 : bucket.usedUnits());
            row.put("heldUnits", bucket == null ? 0 : bucket.heldUnits());
            row.put("remainingUnits", bucket == null ? policy.limitUnits() : bucket.remaining());
            row.put("activationState", bucket == null ? "NOT_ACTIVATED" : bucket.activationState());
            row.put("startAt", bucket == null ? null : bucket.startAt());
            row.put("endAt", bucket == null ? null : bucket.endAt());
            row.put("debtUnits", account.debtUnits());
            policies.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeKind", scopeKind);
        body.put("subjectId", subjectId);
        body.put("policies", policies);
        body.put("pendingReconciliation", repository.pendingReconciliation(scopeKind, subjectId));
        return body;
    }

    public List<Map<String, Object>> usage(
            String scopeKind, String subjectId, int limit, String model, String projectId) {
        return repository.usageRows(scopeKind, subjectId, limit, model, projectId);
    }

    private static BucketView view(
            QuotaRecords.Account account, QuotaRecords.Policy policy, QuotaRecords.Bucket bucket) {
        return new BucketView(
                account.id(),
                account.policyKey(),
                policy.mode(),
                bucket.limitUnits(),
                bucket.usedUnits(),
                bucket.heldUnits(),
                bucket.remaining(),
                bucket.startAt(),
                bucket.endAt(),
                bucket.activationState());
    }

    private record BucketAndAccount(
            QuotaRecords.Account account, QuotaRecords.Policy policy, QuotaRecords.Bucket bucket) {}
}
