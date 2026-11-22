package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillPathRulesTest {

    @Test
    void acceptsStandardTextPackagePaths() {
        assertThat(SkillPathRules.requireValid("SKILL.md")).isEqualTo("SKILL.md");
        assertThat(SkillPathRules.requireValid("references/ykc-1.6-fields.md"))
                .isEqualTo("references/ykc-1.6-fields.md");
        assertThat(SkillPathRules.requireValid("references/notes/check.txt")).isEqualTo("references/notes/check.txt");
        assertThat(SkillPathRules.requireValid("scripts/check.py")).isEqualTo("scripts/check.py");
        assertThat(SkillPathRules.requireValid("assets/template.html")).isEqualTo("assets/template.html");
        assertThat(SkillPathRules.requireValid("LICENSE.txt")).isEqualTo("LICENSE.txt");
    }

    @Test
    void rejectsTraversalAbsoluteAndBinaryPaths() {
        assertThatThrownBy(() -> SkillPathRules.requireValid("../secret.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("穿越段");
        assertThatThrownBy(() -> SkillPathRules.requireValid("references/../../secret.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathRules.requireValid("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathRules.requireValid("references\\secret.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SkillPathRules.requireValid("scripts/run.sh")).isEqualTo("scripts/run.sh");
        assertThatThrownBy(() -> SkillPathRules.requireValid("assets/tool.exe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathRules.requireValid("references/run.exe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathRules.requireValid("references//empty.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resourceReadsOnlyAllowStandardResourceDirectories() {
        assertThat(SkillPathRules.requireResourcePath("references/checklist.md"))
                .isEqualTo("references/checklist.md");
        assertThat(SkillPathRules.requireResourcePath("scripts/check.py")).isEqualTo("scripts/check.py");
        assertThat(SkillPathRules.requireResourcePath("assets/template.html")).isEqualTo("assets/template.html");
        assertThatThrownBy(() -> SkillPathRules.requireResourcePath("SKILL.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read_skill_resource");
        assertThatThrownBy(() -> SkillPathRules.requireResourcePath("LICENSE.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
