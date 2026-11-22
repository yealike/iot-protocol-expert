package top.yealike.pep.control.agents.skill;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.SkillPathRules;

/** Skill 包大小限制与 Control 侧错误转换；路径规则由 Control/Runtime 共享。 */
public final class SkillPaths {

    public static final int MAX_FILES = 50;
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;

    private SkillPaths() {}

    public static String requireValid(String path) {
        try {
            String valid = SkillPathRules.requireValid(path);
            Set<String> folded = new HashSet<>();
            for (String segment : valid.split("/", -1)) {
                if (!folded.add(segment.toLowerCase(Locale.ROOT))) {
                    throw AiExceptions.badRequest("Skill 路径存在大小写冲突");
                }
            }
            return valid;
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest(exception.getMessage());
        }
    }

    public static String normalizeContent(String content) {
        if (content == null) {
            throw AiExceptions.badRequest("Skill 文件内容不能为空");
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String textProblem = textProblem(normalized);
        if (textProblem != null) {
            throw AiExceptions.badRequest(textProblem);
        }
        int bytes = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) {
            throw AiExceptions.badRequest("单个 Skill 文件不能超过 256 KiB");
        }
        return normalized;
    }

    /** PostgreSQL text/jsonb 不接受 NUL，也不接受不成对的 UTF-16 代理项。 */
    public static String textProblem(String content) {
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\0') {
                return "Skill 文件内容不能包含 NUL 字符";
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= content.length() || !Character.isLowSurrogate(content.charAt(index + 1))) {
                    return "Skill 文件内容包含不成对的 Unicode 代理项";
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return "Skill 文件内容包含不成对的 Unicode 代理项";
            }
        }
        return null;
    }
}
