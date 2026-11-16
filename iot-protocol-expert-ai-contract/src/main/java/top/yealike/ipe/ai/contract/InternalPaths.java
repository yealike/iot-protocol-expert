package top.yealike.ipe.ai.contract;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 内部 HTTP 路径只在一处定义，避免 Control 与 Runtime 各自拼接。 */
public final class InternalPaths {

    private InternalPaths() {}

    public static final String CONTROL_BASE = "/internal/ai/v1";
    public static final String RUNTIME_BASE = "/internal/agent/v1";

    public static final String RUNTIME_PREPARE = RUNTIME_BASE + "/releases:prepare";
    public static final String RUNTIME_BASE_SERVICE = "ipe-agent-runtime";

    public static String runtimeRuns(String agentKey, String version) {
        return RUNTIME_BASE + "/agents/" + agentKey + "/versions/" + version + "/runs";
    }

    public static String runtimeRun(UUID runId) {
        return RUNTIME_BASE + "/runs/" + runId;
    }

    public static String runtimeCancel(UUID runId) {
        return RUNTIME_BASE + "/runs/" + runId + ":cancel";
    }

    public static String controlManifest(UUID releaseId) {
        return CONTROL_BASE + "/releases/" + releaseId + "/manifest";
    }

    public static String controlMcpDescriptor(String key, String version) {
        return CONTROL_BASE + "/mcps/" + key + "/versions/" + version + "/descriptor";
    }

    public static String controlSkill(String key, String version) {
        return CONTROL_BASE + "/skills/" + key + "/versions/" + version;
    }

    public static String controlSkillFile(String key, String version, String path) {
        return controlSkill(key, version) + "/file?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    public static String controlClaim(UUID runId) {
        return CONTROL_BASE + "/runs/" + runId + ":claim";
    }

    public static String controlHeartbeat(UUID runId) {
        return CONTROL_BASE + "/runs/" + runId + ":heartbeat";
    }

    public static String controlContext(UUID runId) {
        return CONTROL_BASE + "/runs/" + runId + "/context";
    }

    public static String controlEvents(UUID runId) {
        return CONTROL_BASE + "/runs/" + runId + "/events:batch";
    }

    public static String controlComplete(UUID runId) {
        return CONTROL_BASE + "/runs/" + runId + ":complete";
    }

    public static String controlCredentials() {
        return CONTROL_BASE + "/credentials:resolve";
    }

    public static String controlAuthorize(UUID runId) {
        return CONTROL_BASE + "/runs/" + runId + "/tools:authorize";
    }

    public static String controlDesiredReleases() {
        return CONTROL_BASE + "/runtime/desired-releases";
    }

    public static String controlDesiredReleases(long cursor, int limit) {
        return controlDesiredReleases() + "?cursor=" + cursor + "&limit=" + limit;
    }

    public static String controlReadiness() {
        return CONTROL_BASE + "/runtime/readiness";
    }
}
