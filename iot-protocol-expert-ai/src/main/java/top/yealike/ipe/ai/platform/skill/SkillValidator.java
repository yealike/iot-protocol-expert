package top.yealike.ipe.ai.platform.skill;

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
import top.yealike.ipe.ai.contract.SkillFile;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.CompileIssue;
import top.yealike.ipe.ai.support.AiExceptions;

/** SKILL.md 格式校验：安全 YAML、名称/描述、引用路径与大小上限。 */
@Component
public class SkillValidator {

    private static final Pattern REFERENCE = Pattern.compile("references/[A-Za-z0-9._/-]+");
    private static final Pattern FRONTMATTER = Pattern.compile("(?s)^---\\n(.*?)\\n---\\n(.*)$");

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
            byPath.put(file.path(), file);
        }
        if (totalBytes > SkillPaths.MAX_TOTAL_BYTES) {
            errors.add(new CompileIssue("SKILL_TOO_LARGE", "Skill 总大小超过 2 MiB", "files"));
        }
        SkillFile entry = byPath.get("SKILL.md");
        if (entry == null) {
            errors.add(new CompileIssue("SKILL_ENTRY_MISSING", "缺少 SKILL.md", "SKILL.md"));
            return new Validation(errors, warnings, null);
        }
        if (entry.content().length() > 12000) {
            warnings.add(new CompileIssue("SKILL_ENTRY_LONG", "SKILL.md 超过 12000 字符，建议拆分参考资料", "SKILL.md"));
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
            map.forEach((name, value) -> frontmatter.put(String.valueOf(name), value));
        } catch (RuntimeException exception) {
            errors.add(new CompileIssue("SKILL_FRONTMATTER_INVALID", "frontmatter YAML 无法解析", "SKILL.md"));
            return new Validation(errors, warnings, null);
        }
        String name = string(frontmatter.get("name"));
        String description = string(frontmatter.get("description"));
        if (name == null || !name.equals(key) || name.length() > 40 || name.isEmpty()) {
            errors.add(
                    new CompileIssue("SKILL_NAME_INVALID", "frontmatter name 必须与 Skill key 一致且不超过 40 字符", "SKILL.md"));
        }
        if (description == null || description.isBlank() || description.length() > 1024) {
            errors.add(new CompileIssue("SKILL_DESCRIPTION_INVALID", "description 必须非空且不超过 1024 字符", "SKILL.md"));
        }
        Object metadata = frontmatter.get("metadata");
        if (metadata != null) {
            if (!(metadata instanceof Map<?, ?> map)) {
                errors.add(new CompileIssue("SKILL_METADATA_INVALID", "metadata 必须是 string->string 对象", "SKILL.md"));
            } else {
                for (Map.Entry<?, ?> item : map.entrySet()) {
                    if (!(item.getValue() instanceof String)) {
                        errors.add(new CompileIssue("SKILL_METADATA_INVALID", "metadata 值必须是字符串", "SKILL.md"));
                        break;
                    }
                }
            }
        }
        Matcher references = REFERENCE.matcher(matcher.group(2));
        while (references.find()) {
            String path = references.group();
            if (!byPath.containsKey(path)) {
                errors.add(new CompileIssue("SKILL_REFERENCE_MISSING", "引用的参考资料不存在：" + path, "SKILL.md"));
            }
        }
        return new Validation(errors, warnings, new Metadata(name, description, stringMap(metadata)));
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        }
        return Map.copyOf(values);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record Metadata(String name, String description, Map<String, String> metadata) {}

    public record Validation(List<CompileIssue> errors, List<CompileIssue> warnings, Metadata metadata) {
        public Validation {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}
