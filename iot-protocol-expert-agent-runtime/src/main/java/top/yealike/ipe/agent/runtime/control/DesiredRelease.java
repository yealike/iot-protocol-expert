package top.yealike.ipe.agent.runtime.control;

import java.util.UUID;

/** Control 当前期望 Runtime 准备的已发布 Agent 版本。 */
public record DesiredRelease(
        UUID releaseId, String agentKey, String version, String manifestDigest, long generation, String state) {}
