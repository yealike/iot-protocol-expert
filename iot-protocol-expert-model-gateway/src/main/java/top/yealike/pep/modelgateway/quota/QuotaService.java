package top.yealike.pep.modelgateway.quota;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // PostgreSQL 的 uuid 按无符号字节序比较；规范字符串序与其一致，UUID.compareTo 则按有符号 long。
    private static final Comparator<UUID> DATABASE_UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final int MAX_POLICY_KEY_LENGTH = 64;

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
        repository.lockAdvisory("model-call:" + request.logicalCallId() + ":" + request.attemptNo());
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
            if (!isEffective(policy, now)) {
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
            if (current.limitUnits() != policy.limitUnits() || current.policyRevision() != policy.revision()) {
                repository.updateBucketPolicy(current.id(), policy.limitUnits(), policy.revision());
                current = repository.findBucket(current.id()).orElseThrow();
            }
            return current;
        }
        if (current == null || windowExpired(current, now)) {
            if (current != null && "PROVISIONAL".equals(current.activationState())) {
                repository.updateBucketState(current.id(), "EXPIRED");
            }
            return createBucket(account, policy, now, "WINDOW", "PROVISIONAL", policy.durationSeconds());
        }
        return current;
    }

    private static boolean isEffective(QuotaRecords.Policy policy, OffsetDateTime now) {
        return !policy.effectiveAt().isAfter(now)
                && (policy.expiresAt() == null || policy.expiresAt().isAfter(now));
    }

    private static boolean windowExpired(QuotaRecords.Bucket bucket, OffsetDateTime now) {
        return bucket.endAt() == null
                || !bucket.endAt().isAfter(now)
                || "VOID".equals(bucket.activationState())
                || "EXPIRED".equals(bucket.activationState());
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

    public boolean markDispatched(UUID reservationId) {
        return transactions.execute(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if (!"HELD".equals(reservation.state())) {
                return false;
            }
            if (!repository.markReservationDispatched(reservationId)) {
                return false;
            }
            repository.markCallDispatched(reservation.modelCallId());
            for (QuotaRecords.Charge charge : repository.lockCharges(reservationId)) {
                repository.updateChargeState(charge.id(), "DISPATCHED", null);
                QuotaRecords.Bucket bucket =
                        repository.lockBucket(charge.bucketId()).orElseThrow();
                if ("PROVISIONAL".equals(bucket.activationState())) {
                    repository.updateBucketState(bucket.id(), "ACTIVE");
                }
            }
            return true;
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
                List<QuotaRecords.Charge> charges = lockChargesAndAccounts(reservationId);
                settleReleased(reservation, charges, usage, providerRequestId, rawUsage);
                return;
            }
            long settled = usage.quotaUnits();
            for (QuotaRecords.Charge charge : lockChargesAndAccounts(reservationId)) {
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

    private void settleReleased(
            QuotaRecords.Reservation reservation,
            List<QuotaRecords.Charge> charges,
            ModelUsage usage,
            String providerRequestId,
            String rawUsage) {
        log.warn("usage arrived after release: reservation={} units={}", reservation.id(), usage.quotaUnits());
        long settled = usage.quotaUnits();
        for (QuotaRecords.Charge charge : charges) {
            if (!"RELEASED".equals(charge.state())) {
                continue;
            }
            repository.updateChargeState(charge.id(), "SETTLED", settled);
            repository.settleReleasedBucket(charge.bucketId(), settled);
            repository.appendLedger(
                    charge.accountId(),
                    charge.bucketId(),
                    reservation.id(),
                    "LATE_SETTLED",
                    settled,
                    0,
                    "LATE_SETTLED:" + charge.id());
            QuotaRecords.Bucket bucket =
                    repository.lockBucket(charge.bucketId()).orElseThrow();
            if (bucket.usedUnits() + bucket.heldUnits() > bucket.limitUnits()) {
                repository.markDebt(charge.accountId(), bucket.usedUnits() + bucket.heldUnits() - bucket.limitUnits());
            }
        }
        repository.insertUsage(reservation.modelCallId(), usage, null, rawUsage);
        repository.updateReservationState(reservation.id(), "SETTLED");
        repository.updateCallState(reservation.modelCallId(), "SETTLED", providerRequestId);
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
            boolean releasable = "HELD".equals(reservation.state())
                    || (allowDispatched
                            && ("DISPATCHED".equals(reservation.state()) || "UNCERTAIN".equals(reservation.state())));
            if (!releasable) {
                throw ApiException.conflict(ModelErrorCodes.QUOTA_PENDING_RECONCILIATION, "调用已派发，不能直接释放，请走对账");
            }
            for (QuotaRecords.Charge charge : lockChargesAndAccounts(reservationId)) {
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
            markUncertainLocked(reservation, providerRequestId);
        });
    }

    private void markUncertainLocked(QuotaRecords.Reservation reservation, String providerRequestId) {
        if ("SETTLED".equals(reservation.state()) || "RELEASED".equals(reservation.state())) {
            return;
        }
        repository.updateReservationState(reservation.id(), "UNCERTAIN");
        repository.updateCallState(reservation.modelCallId(), "UNCERTAIN", providerRequestId);
        for (QuotaRecords.Charge charge : repository.lockCharges(reservation.id())) {
            repository.updateChargeState(charge.id(), "UNCERTAIN", null);
        }
    }

    public void reconcileRelease(UUID reservationId) {
        transactions.executeWithoutResult(status -> {
            QuotaRecords.Reservation reservation =
                    repository.lockReservation(reservationId).orElseThrow(() -> ApiException.notFound("预占不存在"));
            if (!"UNCERTAIN".equals(reservation.state())) {
                throw ApiException.conflict(ModelErrorCodes.CALL_CONFLICT, "只有不确定调用可以对账");
            }
            for (QuotaRecords.Charge charge : lockChargesAndAccounts(reservationId)) {
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
        QuotaRecords.Account account =
                repository.lockAccount(accountId).orElseThrow(() -> ApiException.notFound("额度账户不存在"));
        QuotaRecords.Bucket bucket = repository.lockBucket(bucketId).orElseThrow();
        if (!"PROVISIONAL".equals(bucket.activationState())) {
            return;
        }
        if (bucket.usedUnits() > 0 || bucket.heldUnits() > 0) {
            return;
        }
        repository.updateBucketState(bucketId, "VOID");
        if (bucketId.equals(account.currentBucketId())) {
            repository.setCurrentBucket(accountId, null, account.version());
        }
    }

    private List<QuotaRecords.Charge> lockChargesAndAccounts(UUID reservationId) {
        List<QuotaRecords.Charge> charges = repository.lockCharges(reservationId);
        lockChargeAccounts(charges);
        return charges;
    }

    private void lockChargeAccounts(Collection<QuotaRecords.Charge> charges) {
        charges.stream()
                .map(QuotaRecords.Charge::accountId)
                .distinct()
                .sorted(databaseUuidOrder())
                .forEach(accountId ->
                        repository.lockAccount(accountId).orElseThrow(() -> ApiException.notFound("额度账户不存在")));
    }

    static Comparator<UUID> databaseUuidOrder() {
        return DATABASE_UUID_ORDER;
    }

    @Scheduled(fixedDelayString = "${pep.gateway.sweep-interval-ms:30000}")
    public void reconcileExpiredReservations() {
        transactions.executeWithoutResult(status -> {
            List<QuotaRecords.Reservation> heldReservations = repository.expiredHeldReservations(50);
            Map<UUID, List<QuotaRecords.Charge>> chargesByReservation = new LinkedHashMap<>();
            List<QuotaRecords.Charge> allCharges = new ArrayList<>();
            for (QuotaRecords.Reservation reservation : heldReservations) {
                List<QuotaRecords.Charge> charges = repository.lockCharges(reservation.id());
                chargesByReservation.put(reservation.id(), charges);
                allCharges.addAll(charges);
            }
            lockChargeAccounts(allCharges);
            for (QuotaRecords.Reservation reservation : heldReservations) {
                for (QuotaRecords.Charge charge : chargesByReservation.get(reservation.id())) {
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
        transactions.executeWithoutResult(status -> {
            OffsetDateTime cutoff = repository.now().minusSeconds(properties.getReservationTtlSeconds());
            for (QuotaRecords.Reservation reservation : repository.expiredDispatchedReservations(cutoff, 50)) {
                markUncertainLocked(reservation, null);
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
        String policyKey = request.policyKey() == null || request.policyKey().isBlank()
                ? defaultPolicyKey(request, mode)
                : request.policyKey();
        return transactions.execute(status -> {
            repository.lockAdvisory("quota-grant:" + request.actorId() + ":" + request.idempotencyKey());
            var replay = repository.findChangeRequest(request.actorId(), request.idempotencyKey());
            if (replay.isPresent()) {
                requireMatchingReplay(request, mode, policyKey, replay.get());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("accountId", replay.get().accountId().toString());
                body.put("revision", replay.get().newRevision());
                body.put("mode", replay.get().effectiveMode());
                body.put("replayed", true);
                return body;
            }
            repository.lockAdvisory("quota-policy:" + policyKey);
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
            QuotaRecords.Account account = repository.lockAccounts(request.scopeKind(), request.subjectId()).stream()
                    .filter(value -> value.policyKey().equals(policyKey))
                    .findFirst()
                    .orElseThrow();
            long oldRevision = account.activeRevision();
            if (oldRevision != revision) {
                QuotaRecords.Policy previousPolicy =
                        repository.findPolicy(account.policyKey(), oldRevision).orElse(null);
                boolean modeChanged = previousPolicy != null && !mode.equals(previousPolicy.mode());
                if (modeChanged && account.currentBucketId() != null) {
                    repository.setCurrentBucket(account.id(), null, account.version());
                } else if (account.currentBucketId() != null) {
                    repository
                            .lockBucket(account.currentBucketId())
                            .ifPresent(bucket ->
                                    repository.updateBucketPolicy(bucket.id(), request.limitUnits(), revision));
                }
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

    private static String defaultPolicyKey(GrantRequest request, String mode) {
        String key = request.scopeKind().toLowerCase(java.util.Locale.ROOT) + ":" + request.subjectId() + ":" + mode;
        return key.length() <= MAX_POLICY_KEY_LENGTH
                ? key
                : "quota:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void requireMatchingReplay(
            GrantRequest request, String mode, String policyKey, QuotaRecords.ChangeRequest replay) {
        QuotaRecords.Account account = repository
                .findAccount(replay.accountId())
                .orElseThrow(() -> ApiException.conflict(ModelErrorCodes.CALL_CONFLICT, "幂等记录对应的额度账户不存在"));
        QuotaRecords.Policy policy = replay.newRevision() == null
                ? null
                : repository
                        .findPolicy(account.policyKey(), replay.newRevision())
                        .orElse(null);
        String expectedReason = request.reason() == null ? "" : request.reason();
        Long expectedDuration = "ON_FIRST_USE_WINDOW".equals(mode) ? request.durationSeconds() : null;
        boolean matches = Objects.equals(account.scopeKind(), request.scopeKind())
                && Objects.equals(account.subjectId(), request.subjectId())
                && Objects.equals(account.policyKey(), policyKey)
                && "GRANT".equals(replay.action())
                && Objects.equals(replay.effectiveMode(), mode)
                && Objects.equals(replay.reason(), expectedReason)
                && policy != null
                && policy.limitUnits() == request.limitUnits()
                && Objects.equals(policy.durationSeconds(), expectedDuration);
        if (!matches) {
            throw ApiException.conflict(ModelErrorCodes.CALL_CONFLICT, "同一 Idempotency-Key 不能用于不同的额度授予内容");
        }
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
        OffsetDateTime now = repository.now();
        List<QuotaRecords.Account> accounts = repository.accounts(scopeKind, subjectId);
        List<Map<String, Object>> policies = new ArrayList<>();
        long availableUnits = Long.MAX_VALUE;
        for (QuotaRecords.Account account : accounts) {
            QuotaRecords.Policy policy = repository
                    .findPolicy(account.policyKey(), account.activeRevision())
                    .orElse(null);
            if (policy == null) {
                availableUnits = 0;
                continue;
            }
            QuotaRecords.Bucket bucket = account.currentBucketId() == null
                    ? null
                    : repository.findBucket(account.currentBucketId()).orElse(null);
            boolean total = "TOTAL".equals(policy.mode());
            boolean expiredWindow = !total && bucket != null && windowExpired(bucket, now);
            boolean effective = isEffective(policy, now);
            long used = bucket == null ? 0 : bucket.usedUnits();
            long held = bucket == null ? 0 : bucket.heldUnits();
            long remaining = total || bucket == null ? policy.limitUnits() - used - held : bucket.remaining();
            long available = "ACTIVE".equals(account.state()) && effective
                    ? Math.max(0, expiredWindow ? policy.limitUnits() : remaining)
                    : 0;
            availableUnits = Math.min(availableUnits, available);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountId", account.id().toString());
            row.put("policyKey", account.policyKey());
            row.put("mode", policy.mode());
            row.put("state", account.state());
            row.put("limitUnits", policy.limitUnits());
            row.put("durationSeconds", policy.durationSeconds());
            row.put("usedUnits", used);
            row.put("heldUnits", held);
            row.put("remainingUnits", remaining);
            row.put("availableUnits", available);
            row.put("effective", effective);
            row.put("expiredWindow", expiredWindow);
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
        body.put("availableUnits", accounts.isEmpty() ? 0 : availableUnits);
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
