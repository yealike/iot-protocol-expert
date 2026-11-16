package top.yealike.ipe.ai.contract;

import java.time.Instant;
import java.util.UUID;

/** Control 向指定 Runtime 提交执行的内部请求。 */
public record RunDispatchRequest(
        UUID runId,
        UUID releaseId,
        String manifestDigest,
        long executionToken,
        String agentKey,
        String agentVersion,
        UUID conversationId,
        String actorId,
        long authVersion,
        Instant deadline,
        String controlServiceKey,
        String traceparent) {}
