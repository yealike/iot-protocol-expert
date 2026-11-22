package top.yealike.pep.runtime.contract;

import java.util.Locale;
import java.util.Set;

/**
 * Control 与 Runtime 共用的 Skill 包路径规则。
 *
 * <p>只接受标准目录 {@code references/}、{@code scripts/}、{@code assets/} 下的 UTF-8 文本文件。二进制资源不能进入
 * JSON/JSONB bundle，因此不在支持范围内。
 */
public final class SkillPathRules {

    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_DEPTH = 8;

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of(
            "md", "txt", "py", "sh", "bash", "js", "mjs", "cjs", "ts", "ps1", "json", "yaml", "yml", "toml", "sql");
    private static final Set<String> ASSET_EXTENSIONS =
            Set.of("md", "txt", "json", "yaml", "yml", "csv", "tsv", "html", "htm", "css", "svg", "xml");
    private static final Set<String> REFERENCE_EXTENSIONS = Set.of("md", "txt");

    private SkillPathRules() {}

    public static String requireValid(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Skill 文件路径不能为空");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Skill 文件路径超过 512 字符");
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("//")) {
            throw new IllegalArgumentException("Skill 路径必须使用安全的相对路径");
        }
        for (char character : path.toCharArray()) {
            if (character < 0x20 || character == 0x7f) {
                throw new IllegalArgumentException("Skill 路径包含控制字符");
            }
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            throw new IllegalArgumentException("Skill 路径深度超过 8 层");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Skill 路径包含空段或穿越段");
            }
        }
        if ("SKILL.md".equals(path) || "LICENSE.md".equals(path) || "LICENSE.txt".equals(path)) {
            return path;
        }
        String directory = segments[0];
        Set<String> extensions = switch (directory) {
            case "references" -> REFERENCE_EXTENSIONS;
            case "scripts" -> SCRIPT_EXTENSIONS;
            case "assets" -> ASSET_EXTENSIONS;
            default -> Set.of();
        };
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("只允许 SKILL.md、LICENSE.md|txt 与 references/scripts/assets 下的文本文件");
        }
        String fileName = segments[segments.length - 1];
        int dot = fileName.lastIndexOf('.');
        String extension = dot <= 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extensions.contains(extension)) {
            throw new IllegalArgumentException(directory + "/ 目录包含不支持的文本文件扩展名");
        }
        return path;
    }

    /** {@code read_skill_resource} 只允许读取标准资源目录，入口 SKILL.md 由 load_skill 负责。 */
    public static String requireResourcePath(String path) {
        String valid = requireValid(path);
        String directory = valid.split("/", -1)[0];
        if (!"references".equals(directory) && !"scripts".equals(directory) && !"assets".equals(directory)) {
            throw new IllegalArgumentException("read_skill_resource 只允许 references/scripts/assets 下的文件");
        }
        return valid;
    }
}
