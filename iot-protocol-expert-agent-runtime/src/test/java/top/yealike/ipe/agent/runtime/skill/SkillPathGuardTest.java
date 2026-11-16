package top.yealike.ipe.agent.runtime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillPathGuardTest {

    @Test
    void acceptsOnlySkillEntryAndReferenceTextFiles() {
        assertThat(SkillPathGuard.requireSafe("SKILL.md")).isEqualTo("SKILL.md");
        assertThat(SkillPathGuard.requireSafe("references/ykc-1.6-fields.md"))
                .isEqualTo("references/ykc-1.6-fields.md");
        assertThat(SkillPathGuard.requireSafe("references/notes/check.txt")).isEqualTo("references/notes/check.txt");
    }

    @Test
    void rejectsTraversalAbsoluteAndExecutablePaths() {
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("../secret.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL_PATH_DENIED");
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("references/../../secret.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("references\\secret.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("scripts/run.sh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("references/run.exe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillPathGuard.requireSafe("references//empty.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
