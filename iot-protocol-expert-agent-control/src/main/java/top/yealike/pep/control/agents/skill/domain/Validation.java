package top.yealike.pep.control.agents.skill.domain;

import java.util.List;
import top.yealike.pep.control.agents.domain.CompileIssue;

public record Validation(List<CompileIssue> errors, List<CompileIssue> warnings, Metadata metadata) {
    public Validation {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }
}
