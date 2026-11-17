package top.yealike.pep.runtime.skill;

import java.util.Locale;

/** Skill 路径校验：与 Control 发布校验保持同一规则，任何越界都拒绝。 */
public final class SkillPathGuard {

    private static final int MAX_LENGTH = 512;
    private static final int MAX_DEPTH = 8;

    private SkillPathGuard() {}

    public static String requireSafe(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("SKILL_PATH_DENIED: 路径不能为空");
        }
        if (path.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("SKILL_PATH_DENIED: 路径过长");
        }
        if (path.indexOf('\\') >= 0
                || path.startsWith("/")
                || path.matches("^[A-Za-z]:.*")
                || path.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("SKILL_PATH_DENIED: 路径格式非法");
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            throw new IllegalArgumentException("SKILL_PATH_DENIED: 路径层级过深");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("SKILL_PATH_DENIED: 路径包含非法片段");
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!"skill.md".equals(lower)
                && !(lower.startsWith("references/") && (lower.endsWith(".md") || lower.endsWith(".txt")))) {
            throw new IllegalArgumentException("SKILL_PATH_DENIED: 仅允许 SKILL.md 与 references 下的文本资料");
        }
        return path;
    }

    public static String normalizedKey(String path) {
        return requireSafe(path).toLowerCase(Locale.ROOT);
    }
}
