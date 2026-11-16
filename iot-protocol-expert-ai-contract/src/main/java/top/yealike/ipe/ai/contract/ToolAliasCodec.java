package top.yealike.ipe.ai.contract;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** 模型可见工具别名。真实身份始终是 (mcpKey, version, originalName, schemaDigest)。 */
public final class ToolAliasCodec {

    private static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED = Pattern.compile("[^A-Za-z0-9_-]");

    private ToolAliasCodec() {}

    public static String alias(String mcpKey, String originalToolName) {
        String sanitizedKey = sanitize(mcpKey);
        String sanitizedTool = sanitize(originalToolName);
        String base = "mcp_" + sanitizedKey + "__" + sanitizedTool;
        boolean changed = !sanitizedKey.equals(mcpKey) || !sanitizedTool.equals(originalToolName);
        if (!changed && base.length() <= MAX_LENGTH) {
            return base;
        }
        String suffix = "_"
                + Digests.sha256Hex((mcpKey + "\u0000" + originalToolName).getBytes(StandardCharsets.UTF_8))
                        .substring(0, 10);
        int keep = Math.max(1, MAX_LENGTH - suffix.length());
        if (base.length() > keep) {
            base = base.substring(0, keep);
        }
        return base + suffix;
    }

    public static String sanitize(String value) {
        String normalized = value == null ? "tool" : value.trim().toLowerCase(Locale.ROOT);
        normalized = ALLOWED.matcher(normalized).replaceAll("_");
        return normalized.isBlank() ? "tool" : normalized;
    }
}
