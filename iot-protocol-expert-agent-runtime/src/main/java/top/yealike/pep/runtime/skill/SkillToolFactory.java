package top.yealike.pep.runtime.skill;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import top.yealike.pep.runtime.config.RuntimeProperties;
import top.yealike.pep.runtime.contract.SkillBundle;
import top.yealike.pep.runtime.contract.SkillFile;
import top.yealike.pep.runtime.execution.RunEventSink;
import top.yealike.pep.runtime.execution.RunGuard;
import top.yealike.pep.runtime.execution.ToolAuditMetadata;
import top.yealike.pep.runtime.prepare.PreparedRelease;

/** 文本 Skill 的两个只读工具；不物化文件、不执行脚本、不访问任意路径。 */
@Component
public class SkillToolFactory {

    private final RuntimeProperties properties;

    public SkillToolFactory(RuntimeProperties properties) {
        this.properties = properties;
    }

    public List<AgentTool> tools(PreparedRelease release, RunEventSink sink, RunGuard guard) {
        if (release.skills().isEmpty()) {
            return List.of();
        }
        Set<String> loaded = ConcurrentHashMap.newKeySet();
        return List.of(
                new LoadSkillTool(release, sink, loaded, guard),
                new ReadSkillResourceTool(release, sink, loaded, guard));
    }

    private final class LoadSkillTool implements AgentTool {

        private final PreparedRelease release;
        private final RunEventSink sink;
        private final Set<String> loaded;
        private final RunGuard guard;
        private final Map<String, Object> parameters;

        private LoadSkillTool(PreparedRelease release, RunEventSink sink, Set<String> loaded, RunGuard guard) {
            this.release = release;
            this.sink = sink;
            this.loaded = loaded;
            this.guard = guard;
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("skillKey", Map.of("type", "string", "description", "已绑定 Skill 的 key"));
            this.parameters = Map.of(
                    "type",
                    "object",
                    "properties",
                    properties,
                    "required",
                    List.of("skillKey"),
                    "additionalProperties",
                    false);
        }

        @Override
        public String getName() {
            return "load_skill";
        }

        @Override
        public String getDescription() {
            return "加载当前 Agent 已绑定 Skill 的 SKILL.md 正文。只接受已绑定 key；需要参考资料时再调用 read_skill_resource。";
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> load(param.getInput()));
        }

        private ToolResultBlock load(Map<String, Object> input) {
            try {
                guard.reserveToolCall();
                guard.ensureActive();
            } catch (RunGuard.RunAbortedException exception) {
                return error(exception.code() + ": " + exception.getMessage());
            }
            String key;
            try {
                key = string(input, "skillKey");
            } catch (IllegalArgumentException exception) {
                return error("BAD_REQUEST: " + exception.getMessage());
            }
            SkillBundle bundle = release.skill(key);
            if (bundle == null) {
                return error("SKILL_PATH_DENIED: 未绑定 Skill " + key);
            }
            SkillFile entry = bundle.files().stream()
                    .filter(file -> "SKILL.md".equalsIgnoreCase(file.path()))
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                return error("SKILL_PATH_DENIED: Skill 缺少 SKILL.md");
            }
            if (!loaded.add("skill:" + key)) {
                return ToolResultBlock.text("Skill 已在本 Run 中加载，无需重复加载：" + key);
            }
            sink.emit("skill.loaded", Map.of("skillKey", key, "version", bundle.version()));
            return ToolResultBlock.text(
                    truncate(entry.content(), properties.skills().maxReadChars()));
        }
    }

    private final class ReadSkillResourceTool implements AgentTool {

        private final PreparedRelease release;
        private final RunEventSink sink;
        private final Set<String> loaded;
        private final RunGuard guard;
        private final Map<String, Object> parameters;

        private ReadSkillResourceTool(PreparedRelease release, RunEventSink sink, Set<String> loaded, RunGuard guard) {
            this.release = release;
            this.sink = sink;
            this.loaded = loaded;
            this.guard = guard;
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("skillKey", Map.of("type", "string"));
            properties.put("path", Map.of("type", "string", "description", "references/ 下的相对路径"));
            properties.put("offset", Map.of("type", "integer", "minimum", 0));
            this.parameters = Map.of(
                    "type",
                    "object",
                    "properties",
                    properties,
                    "required",
                    List.of("skillKey", "path"),
                    "additionalProperties",
                    false);
        }

        @Override
        public String getName() {
            return "read_skill_resource";
        }

        @Override
        public String getDescription() {
            return "读取当前 Agent 已绑定 Skill 的参考文件。只允许 references/ 下的 .md/.txt，单次返回超长内容会分页。";
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> read(param.getInput()));
        }

        private ToolResultBlock read(Map<String, Object> input) {
            try {
                guard.reserveToolCall();
                guard.ensureActive();
            } catch (RunGuard.RunAbortedException exception) {
                return error(exception.code() + ": " + exception.getMessage());
            }
            String key;
            String path;
            try {
                key = string(input, "skillKey");
                path = SkillPathGuard.requireSafe(string(input, "path"));
            } catch (IllegalArgumentException exception) {
                return error(exception.getMessage());
            }
            SkillBundle bundle = release.skill(key);
            if (bundle == null) {
                return error("SKILL_PATH_DENIED: 未绑定 Skill " + key);
            }
            SkillFile file = bundle.files().stream()
                    .filter(item -> item.path().equalsIgnoreCase(path))
                    .findFirst()
                    .orElse(null);
            if (file == null) {
                return error("SKILL_PATH_DENIED: 文件不存在 " + path);
            }
            int offset = integer(input, "offset", 0);
            String content = file.content();
            int start = Math.min(Math.max(0, offset), content.length());
            int max = properties.skills().maxReadChars();
            int end = Math.min(content.length(), start + max);
            String dedupKey = "resource:" + key + ":" + path.toLowerCase(java.util.Locale.ROOT) + ":" + start;
            if (!loaded.add(dedupKey)) {
                return ToolResultBlock.text("该 Skill 分页已在本 Run 中加载，无需重复读取：" + path);
            }
            sink.emit("skill.loaded", Map.of("skillKey", key, "path", path, "offset", start, "chars", end - start));
            return ToolResultBlock.text(paginatedJson(key, path, content, start, max));
        }
    }

    private static ToolResultBlock error(String message) {
        if (message != null && message.startsWith("SKILL_PATH_DENIED")) {
            return ToolResultBlock.builder()
                    .state(ToolResultState.DENIED)
                    .output(TextBlock.builder().text("Error: " + message).build())
                    .metadata(Map.of(
                            ToolAuditMetadata.ERROR_CODE,
                            "SKILL_PATH_DENIED",
                            ToolAuditMetadata.DECISION,
                            ToolAuditMetadata.DENIED))
                    .build();
        }
        return ToolResultBlock.error(message);
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return String.valueOf(value);
    }

    private static int integer(Map<String, Object> input, String key, int defaultValue) {
        Object value = input == null ? null : input.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static String truncate(String content, int max) {
        if (content.length() <= max) {
            return content;
        }
        String marker = "\n\n[内容已截断，原长度 " + content.length() + " 字符]";
        int keep = Math.max(0, max - marker.length());
        return content.substring(0, keep) + marker;
    }

    /**
     * 分页结果包含路径和偏移等 JSON 元数据，content 需要为元数据预留空间，保证整条工具结果不超过上限。
     */
    private static String paginatedJson(String key, String path, String content, int start, int maxChars) {
        int contentLimit = Math.max(1, maxChars);
        String json = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            int end = Math.min(content.length(), start + contentLimit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skillKey", key);
            result.put("path", path);
            result.put("offset", start);
            result.put("content", content.substring(start, end));
            boolean truncated = end < content.length();
            result.put("truncated", truncated);
            if (truncated) {
                result.put("nextOffset", end);
            }
            json = toJson(result);
            if (json.length() <= maxChars || contentLimit <= 1) {
                return json;
            }
            contentLimit = Math.max(1, contentLimit - (json.length() - maxChars) - 16);
        }
        return json;
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
