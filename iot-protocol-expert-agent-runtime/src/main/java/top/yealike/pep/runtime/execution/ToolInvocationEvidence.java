package top.yealike.pep.runtime.execution;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import top.yealike.pep.observability.SensitiveDataSanitizer;

/** 有界收集单个工具调用的参数和结果，供审计表与 Judge 使用。 */
final class ToolInvocationEvidence {

    private final int maxChars;
    private final StringBuilder arguments = new StringBuilder();
    private final StringBuilder result = new StringBuilder();
    private boolean argumentsTruncated;
    private boolean resultTruncated;

    ToolInvocationEvidence(int maxChars) {
        this.maxChars = Math.max(1, maxChars);
    }

    void appendArguments(String value) {
        argumentsTruncated |= append(arguments, value);
    }

    void appendResult(String value) {
        resultTruncated |= append(result, value);
    }

    void appendData(ContentBlock block) {
        appendResult(dataSummary(block));
    }

    Map<String, Object> argumentsEvidence() {
        return evidence(arguments, argumentsTruncated);
    }

    Map<String, Object> resultEvidence() {
        return evidence(result, resultTruncated);
    }

    boolean hasArguments() {
        return !arguments.isEmpty();
    }

    boolean hasResult() {
        return !result.isEmpty();
    }

    private Map<String, Object> evidence(StringBuilder value, boolean truncated) {
        return Map.of("text", SensitiveDataSanitizer.sanitize(value.toString(), maxChars), "truncated", truncated);
    }

    private boolean append(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int remaining = maxChars - target.length();
        if (remaining <= 0) {
            return true;
        }
        int end = Math.min(value.length(), remaining);
        if (end > 0
                && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        target.append(value, 0, end);
        return end < value.length();
    }

    private static String dataSummary(ContentBlock block) {
        if (block == null) {
            return "{\"type\":\"null\"}";
        }
        if (!(block instanceof DataBlock data)) {
            return "{\"type\":\"" + block.getClass().getSimpleName() + "\"}";
        }
        StringBuilder summary = new StringBuilder("{\"type\":\"DataBlock\"");
        appendField(summary, "id", data.getId());
        appendField(summary, "name", data.getName());
        Source source = data.getSource();
        if (source instanceof Base64Source base64) {
            appendField(summary, "source", "Base64Source");
            appendField(summary, "mediaType", base64.getMediaType());
            summary.append(",\"dataLength\":")
                    .append(base64.getData() == null ? 0 : base64.getData().length());
            appendField(summary, "sha256", sha256(base64.getData()));
        } else if (source instanceof URLSource url) {
            appendField(summary, "source", "URLSource");
            appendField(summary, "mimeType", url.getMimeType());
            appendField(summary, "urlSha256", sha256(url.getUrl()));
        } else if (source != null) {
            appendField(summary, "source", source.getClass().getSimpleName());
        }
        return summary.append('}').toString();
    }

    private static void appendField(StringBuilder target, String name, String value) {
        if (value == null) {
            return;
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        target.append(",\"").append(name).append("\":\"").append(escaped).append('"');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
