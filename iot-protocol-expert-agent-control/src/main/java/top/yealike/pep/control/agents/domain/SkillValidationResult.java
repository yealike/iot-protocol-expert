package top.yealike.pep.control.agents.domain;

import java.util.List;
import top.yealike.pep.control.agents.skill.domain.Metadata;

/** Skill 草稿校验结果。 */
public record SkillValidationResult(
        boolean valid,
        List<CompileIssue> errors,
        List<CompileIssue> warnings,
        Metadata metadata,
        long draftRevision,
        int fileCount,
        long totalBytes) {

    public SkillValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
