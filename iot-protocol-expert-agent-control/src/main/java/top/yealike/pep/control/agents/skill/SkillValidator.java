package top.yealike.pep.control.agents.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import top.yealike.pep.control.agents.domain.CompileIssue;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.SkillFile;

/** SKILL.md 格式校验：安全 YAML、名称/描述、引用路径与大小上限。 */
@Component
public class SkillValidator {

    private static final Pattern REFERENCE =
            Pattern.compile("(?<![A-Za-z0-9_./:-])(?:references|scripts|assets)/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern FRONTMATTER = Pattern.compile("(?s)^---\\n(.*?)\\n---(?:\\n(.*))?$");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("name", "description", "license", "compatibility", "metadata", "allowed-tools");

    public Validation validate(String key, List<SkillFile> files) {
        List<CompileIssue> errors = new ArrayList<>();
        List<CompileIssue> warnings = new ArrayList<>();
        if (files.isEmpty()) {
            errors.add(new CompileIssue("SKILL_EMPTY", "Skill 至少需要一个 SKILL.md", "files"));
            return new Validation(errors, warnings, null);
        }
        if (files.size() > SkillPaths.MAX_FILES) {
            errors.add(new CompileIssue("SKILL_TOO_MANY_FILES", "Skill 文件数超过 50", "files"));
        }
        long totalBytes = 0;
        boolean hasScripts = false;
        Set<String> folded = new HashSet<>();
        Map<String, SkillFile> byPath = new LinkedHashMap<>();
        for (SkillFile file : files) {
            try {
                SkillPaths.requireValid(file.path());
            } catch (AiExceptions.AiException exception) {
                errors.add(new CompileIssue("SKILL_PATH_INVALID", exception.getMessage(), file.path()));
                continue;
            }
            if (!folded.add(file.path().toLowerCase(java.util.Locale.ROOT))) {
                errors.add(new CompileIssue("SKILL_PATH_CASE_CONFLICT", "路径大小写冲突", file.path()));
            }
            long bytes = file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            totalBytes += bytes;
            if (bytes > SkillPaths.MAX_FILE_BYTES) {
                errors.add(new CompileIssue("SKILL_FILE_TOO_LARGE", "单个 Skill 文件不能超过 256 KiB", file.path()));
            }
            String textProblem = SkillPaths.textProblem(file.content());
            if (textProblem != null) {
                errors.add(new CompileIssue("SKILL_TEXT_INVALID", textProblem, file.path()));
            }
            if (file.path().startsWith("scripts/")) {
                hasScripts = true;
            }
            byPath.put(file.path(), file);
        }
        if (totalBytes > SkillPaths.MAX_TOTAL_BYTES) {
            errors.add(new CompileIssue("SKILL_TOO_LARGE", "Skill 总大小超过 2 MiB", "files"));
        }
        if (hasScripts) {
            warnings.add(new CompileIssue(
                    "SKILL_SCRIPTS_READ_ONLY", "scripts/ 下的文件只会被 read_skill_resource 读取，平台不会执行脚本", "scripts"));
        }
        SkillFile entry = byPath.get("SKILL.md");
        if (entry == null) {
            errors.add(new CompileIssue("SKILL_ENTRY_MISSING", "缺少 SKILL.md", "SKILL.md"));
            return new Validation(errors, warnings, null);
        }
        if (entry.content().length() > 12000 || entry.content().lines().count() > 500) {
            warnings.add(new CompileIssue("SKILL_ENTRY_LONG", "SKILL.md 超过 500 行或 12000 字符，建议拆分参考资料", "SKILL.md"));
        }
        Matcher matcher = FRONTMATTER.matcher(entry.content());
        if (!matcher.matches()) {
            errors.add(new CompileIssue("SKILL_FRONTMATTER_MISSING", "SKILL.md 必须以 --- 开头并包含 frontmatter", "SKILL.md"));
            return new Validation(errors, warnings, null);
        }
        Map<String, Object> frontmatter;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(10);
            options.setNestingDepthLimit(10);
            options.setCodePointLimit(1_000_000);
            Object loaded = new Yaml(new SafeConstructor(options)).load(matcher.group(1));
            if (!(loaded instanceof Map<?, ?> map)) {
                errors.add(new CompileIssue("SKILL_FRONTMATTER_INVALID", "frontmatter 必须是对象", "SKILL.md"));
                return new Validation(errors, warnings, null);
            }
            frontmatter = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : map.entrySet()) {
                if (!(item.getKey() instanceof String field)) {
                    errors.add(new CompileIssue("SKILL_FRONTMATTER_INVALID", "frontmatter 字段名必须是字符串", "SKILL.md"));
                    continue;
                }
                frontmatter.put(field, item.getValue());
            }
        } catch (RuntimeException exception) {
            errors.add(new CompileIssue("SKILL_FRONTMATTER_INVALID", "frontmatter YAML 无法解析", "SKILL.md"));
            return new Validation(errors, warnings, null);
        }
        for (String field : frontmatter.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                errors.add(new CompileIssue(
                        "SKILL_FRONTMATTER_UNKNOWN_FIELD",
                        "frontmatter 包含未定义字段：" + field + "；扩展信息请放入 metadata",
                        "SKILL.md"));
            }
        }
        String name = stringField(frontmatter.get("name"), "name", errors);
        if (name == null
                || !name.equals(key)
                || name.length() > 40
                || !NAME.matcher(name).matches()) {
            errors.add(new CompileIssue(
                    "SKILL_NAME_INVALID", "frontmatter name 必须是 2-40 位小写字母/数字，以单连字符分段，并与 Skill key 一致", "SKILL.md"));
        }
        String description = stringField(frontmatter.get("description"), "description", errors);
        if (description == null || description.isBlank() || description.length() > 1024) {
            errors.add(new CompileIssue("SKILL_DESCRIPTION_INVALID", "description 必须非空且不超过 1024 字符", "SKILL.md"));
        }
        String license = optionalStringField(frontmatter.get("license"), "license", 0, errors);
        String compatibility = optionalStringField(frontmatter.get("compatibility"), "compatibility", 500, errors);
        String allowedTools = optionalStringField(frontmatter.get("allowed-tools"), "allowed-tools", 0, errors);
        if (allowedTools != null) {
            warnings.add(new CompileIssue(
                    "SKILL_ALLOWED_TOOLS_NOT_ENFORCED",
                    "allowed-tools 仅作为 Skill 元数据保留；实际工具权限仍由 Agent 绑定与平台策略决定",
                    "SKILL.md"));
        }
        Object metadata = frontmatter.get("metadata");
        if (metadata != null) {
            if (!(metadata instanceof Map<?, ?> map)) {
                errors.add(new CompileIssue("SKILL_METADATA_INVALID", "metadata 必须是 string->string 对象", "SKILL.md"));
            } else {
                for (Map.Entry<?, ?> item : map.entrySet()) {
                    if (!(item.getKey() instanceof String) || !(item.getValue() instanceof String)) {
                        errors.add(new CompileIssue("SKILL_METADATA_INVALID", "metadata 值必须是字符串", "SKILL.md"));
                        break;
                    }
                }
            }
        }
        String body = matcher.group(2) == null ? "" : matcher.group(2);
        Matcher references = REFERENCE.matcher(body);
        while (references.find()) {
            String path = trimTrailingPunctuation(references.group());
            if (!byPath.containsKey(path)) {
                errors.add(new CompileIssue("SKILL_REFERENCE_MISSING", "引用的参考资料不存在：" + path, "SKILL.md"));
            }
        }
        return new Validation(
                errors,
                warnings,
                new Metadata(name, description, stringMap(metadata), license, compatibility, allowedTools));
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        }
        return Map.copyOf(values);
    }

    private static String stringField(Object value, String field, List<CompileIssue> errors) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        errors.add(new CompileIssue("SKILL_FRONTMATTER_INVALID", "frontmatter " + field + " 必须是字符串", "SKILL.md"));
        return null;
    }

    private static String optionalStringField(Object value, String field, int maxLength, List<CompileIssue> errors) {
        String text = stringField(value, field, errors);
        if (value == null || text == null) {
            return text;
        }
        if (text.isBlank() || maxLength > 0 && text.length() > maxLength) {
            errors.add(new CompileIssue(
                    "SKILL_FRONTMATTER_INVALID",
                    "frontmatter " + field + " 必须非空" + (maxLength > 0 ? "且不超过 " + maxLength + " 字符" : ""),
                    "SKILL.md"));
            return null;
        }
        return text;
    }

    private static String trimTrailingPunctuation(String path) {
        int end = path.length();
        while (end > 0 && ".,;:!?)]}".indexOf(path.charAt(end - 1)) >= 0) {
            end--;
        }
        return path.substring(0, end);
    }

    public record Metadata(
            String name,
            String description,
            Map<String, String> metadata,
            String license,
            String compatibility,
            String allowedTools) {
        public Metadata(String name, String description, Map<String, String> metadata) {
            this(name, description, metadata, null, null, null);
        }
    }

    public record Validation(List<CompileIssue> errors, List<CompileIssue> warnings, Metadata metadata) {
        public Validation {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}
