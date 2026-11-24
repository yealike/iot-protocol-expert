package top.yealike.pep.runtime.contract;

import java.util.List;
import java.util.UUID;

/** 发布时冻结的 Agent 委派绑定。 */
public record DelegateBinding(
        String alias,
        String targetAgentKey,
        UUID targetReleaseId,
        String targetVersion,
        String targetManifestDigest,
        String description,
        String whenToUse,
        List<String> allowedTools,
        boolean required,
        int maxCalls,
        int timeoutMs,
        int order) {

    public DelegateBinding {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
