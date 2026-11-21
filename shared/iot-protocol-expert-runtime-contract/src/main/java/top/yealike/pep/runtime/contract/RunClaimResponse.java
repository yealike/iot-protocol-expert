package top.yealike.pep.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** claim/heartbeat 的统一响应；cancelRequired 为 true 时 Runtime 必须立即停止。 */
public record RunClaimResponse(
        boolean allowed,
        String reason,
        String owner,
        Instant deadline,
        boolean cancelRequired,
        UUID conversationId,
        String actorId,
        long authVersion,
        List<String> roles,
        AgentManifest.Budget budget) {}
