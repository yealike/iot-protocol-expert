package top.yealike.ipe.ai.contract;

import java.util.Locale;

/**
 * 组件 Nacos 服务名编码：{@code ipe-mcp--ops--v1-0-0}。
 *
 * <p>普通 Naming 服务名只允许有限字符；这里用固定分隔符和版本点转横线保证可读与稳定。
 */
public final class ComponentNames {

    private static final String SEPARATOR = "--";

    private ComponentNames() {}

    public static String serviceName(ComponentType type, String key, String version) {
        return type.servicePrefix() + SEPARATOR + normalize(key) + SEPARATOR + "v" + normalize(version);
    }

    public static String skillPath(String key, String version) {
        return InternalPaths.controlSkill(key, version);
    }

    public static String agentPath(String key, String version) {
        return InternalPaths.runtimeRuns(key, version);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("组件 key/version 不能为空");
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("组件 key/version 无法编码：" + value);
        }
        return normalized;
    }
}
