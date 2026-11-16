package top.yealike.ipe.ai.platform.skill;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import top.yealike.ipe.ai.support.AiExceptions;

/** Skill 相对路径与大小限制。只允许 UTF-8 Markdown/纯文本参考资料。 */
public final class SkillPaths {

    public static final int MAX_FILES = 50;
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_DEPTH = 8;

    private SkillPaths() {}

    public static String requireValid(String path) {
        if (path == null || path.isBlank()) {
            throw AiExceptions.badRequest("Skill 文件路径不能为空");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw AiExceptions.badRequest("Skill 文件路径超过 512 字符");
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("//")) {
            throw AiExceptions.badRequest("Skill 路径必须使用安全的相对路径");
        }
        for (char character : path.toCharArray()) {
            if (character < 0x20 || character == 0x7f) {
                throw AiExceptions.badRequest("Skill 路径包含控制字符");
            }
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            throw AiExceptions.badRequest("Skill 路径深度超过 8 层");
        }
        Set<String> folded = new HashSet<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw AiExceptions.badRequest("Skill 路径包含空段或穿越段");
            }
            if (!folded.add(segment.toLowerCase(Locale.ROOT))) {
                throw AiExceptions.badRequest("Skill 路径存在大小写冲突");
            }
        }
        if (!"SKILL.md".equals(path) && !path.startsWith("references/")) {
            throw AiExceptions.badRequest("只允许 SKILL.md 与 references/**/*.md|txt");
        }
        if (!"SKILL.md".equals(path) && !(path.endsWith(".md") || path.endsWith(".txt"))) {
            throw AiExceptions.badRequest("参考资料只允许 .md 或 .txt");
        }
        return path;
    }

    public static String normalizeContent(String content) {
        if (content == null) {
            throw AiExceptions.badRequest("Skill 文件内容不能为空");
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        int bytes = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) {
            throw AiExceptions.badRequest("单个 Skill 文件不能超过 256 KiB");
        }
        return normalized;
    }
}
