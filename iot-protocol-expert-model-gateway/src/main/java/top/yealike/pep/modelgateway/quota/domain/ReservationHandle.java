package top.yealike.pep.modelgateway.quota.domain;

import java.util.List;
import java.util.UUID;

/** 预占成功结果；replayed 表示命中了同一逻辑调用的既有预占。 */
public record ReservationHandle(
        UUID reservationId, UUID callId, long reservedUnits, List<BucketView> buckets, boolean replayed) {}
