package top.yealike.pep.control.agents.domain;

import java.util.Map;
import java.util.UUID;

/** 已认领的发布事件。 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        long generation,
        String eventType,
        Map<String, Object> payload,
        int attempts) {}
