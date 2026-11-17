package top.yealike.pep.runtime.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Runtime 源事件：sourceSeq 在单 Run 内从 1 单调递增，eventId 用于跨实例幂等。 */
public record RunEvent(String type, Map<String, Object> payload, long sourceSeq, UUID eventId, Instant createdAt) {

    public RunEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
