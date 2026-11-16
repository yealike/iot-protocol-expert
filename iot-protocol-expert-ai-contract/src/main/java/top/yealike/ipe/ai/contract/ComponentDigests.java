package top.yealike.ipe.ai.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨进程共享的组件摘要算法。
 *
 * <p>Control 发布与 Runtime 校验必须使用同一规则，否则会把正常工具误判为 Schema 漂移。
 */
public final class ComponentDigests {

    private ComponentDigests() {}

    /** MCP 工具输入 Schema 摘要：整个 inputSchema 的规范 JSON。 */
    public static String mcpTool(Map<String, Object> inputSchema) {
        return McpToolSchemas.digest(inputSchema);
    }

    /** 工具目录摘要：按工具名排序后的 name+schemaDigest 列表。 */
    public static String mcpCatalog(List<McpToolDescriptor> tools) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        if (tools != null) {
            tools.stream().sorted(Comparator.comparing(McpToolDescriptor::name)).forEach(tool -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", tool.name());
                item.put("schemaDigest", tool.schemaDigest());
                canonical.add(item);
            });
        }
        return Digests.digest(canonical);
    }

    /** Skill 包摘要：key、version 与按路径排序的文件摘要。 */
    public static String skillBundle(SkillBundle bundle) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("key", bundle.key());
        canonical.put("version", bundle.version());
        List<Map<String, Object>> files = new ArrayList<>();
        bundle.files().stream().sorted(Comparator.comparing(SkillFile::path)).forEach(file -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("digest", file.digest());
            files.add(item);
        });
        canonical.put("files", files);
        return Digests.digest(canonical);
    }
}
