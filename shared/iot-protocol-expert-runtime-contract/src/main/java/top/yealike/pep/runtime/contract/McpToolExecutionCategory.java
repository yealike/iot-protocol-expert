package top.yealike.pep.runtime.contract;

import java.util.Locale;

/** 平台审核后的 MCP 工具执行类别；一期仅允许 READ_ONLY。 */
public enum McpToolExecutionCategory {
    READ_ONLY,
    WRITE,
    UNKNOWN;

    public static McpToolExecutionCategory parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }

    public boolean readOnly() {
        return this == READ_ONLY;
    }
}
