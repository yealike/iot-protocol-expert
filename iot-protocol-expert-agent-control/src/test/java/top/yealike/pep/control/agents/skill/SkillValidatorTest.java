package top.yealike.pep.control.agents.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.contract.SkillFile;

class SkillValidatorTest {

    private final SkillValidator validator = new SkillValidator();

    @Test
    void acceptsAgentSkillsFrontmatterAndTextResources() {
        String entry = """
                ---
                name: device-offline-check
                description: Diagnose device offline causes from protocol traces
                license: Apache-2.0
                compatibility: Requires network access
                metadata:
                  owner: iot-platform
                allowed-tools: read_file
                ---

                Read [the checklist](references/checklist.md), then run scripts/check.py.
                Report the template in assets/report.html.
                """;

        SkillValidator.Validation result = validator.validate(
                "device-offline-check",
                List.of(
                        file("SKILL.md", entry),
                        file("references/checklist.md", "# Checklist\n"),
                        file("scripts/check.py", "print('ok')\n"),
                        file("assets/report.html", "<h1>Report</h1>\n")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.metadata().license()).isEqualTo("Apache-2.0");
        assertThat(result.metadata().compatibility()).isEqualTo("Requires network access");
        assertThat(result.metadata().allowedTools()).isEqualTo("read_file");
        assertThat(result.warnings()).extracting(issue -> issue.code()).contains("SKILL_ALLOWED_TOOLS_NOT_ENFORCED");
    }

    @Test
    void rejectsNonStandardNameAndUnknownFrontmatterFields() {
        String entry = """
                ---
                name: Bad--Name
                description: Invalid
                version: 1
                ---
                body
                """;

        SkillValidator.Validation result = validator.validate("bad-name", List.of(file("SKILL.md", entry)));

        assertThat(result.errors())
                .extracting(issue -> issue.code())
                .contains("SKILL_NAME_INVALID", "SKILL_FRONTMATTER_UNKNOWN_FIELD");
    }

    @Test
    void rejectsOversizedCompatibilityAndMissingScriptReference() {
        String entry = """
                ---
                name: protocol-check
                description: Check a protocol payload
                compatibility: %s
                ---
                Run scripts/missing.py.
                """.formatted("x".repeat(501));

        SkillValidator.Validation result = validator.validate("protocol-check", List.of(file("SKILL.md", entry)));

        assertThat(result.errors())
                .extracting(issue -> issue.code())
                .contains("SKILL_FRONTMATTER_INVALID", "SKILL_REFERENCE_MISSING");
    }

    @Test
    void rejectsNonStringMetadataValues() {
        String entry = """
                ---
                name: protocol-check
                description: Check a protocol payload
                metadata:
                  retries: 3
                ---
                body
                """;

        SkillValidator.Validation result = validator.validate("protocol-check", List.of(file("SKILL.md", entry)));

        assertThat(result.errors()).extracting(issue -> issue.code()).contains("SKILL_METADATA_INVALID");
    }

    @Test
    void rejectsOversizedFileAndNulWhileWarningReadOnlyScripts() {
        String entry = """
                ---
                name: protocol-check
                description: Check a protocol payload
                ---
                Run scripts/check.py.
                """;

        SkillValidator.Validation result = validator.validate(
                "protocol-check",
                List.of(
                        file("SKILL.md", entry),
                        file("scripts/check.py", "print('ok')\n"),
                        file("references/large.md", "x".repeat(256 * 1024 + 1)),
                        file("references/nul.md", "bad\0text")));

        assertThat(result.errors())
                .extracting(issue -> issue.code())
                .contains("SKILL_FILE_TOO_LARGE", "SKILL_TEXT_INVALID");
        assertThat(result.warnings()).extracting(issue -> issue.code()).contains("SKILL_SCRIPTS_READ_ONLY");
    }

    @Test
    void acceptsFrontmatterWithoutTrailingNewline() {
        String entry = """
                ---
                name: protocol-check
                description: Check a protocol payload
                ---""";

        SkillValidator.Validation result = validator.validate("protocol-check", List.of(file("SKILL.md", entry)));

        assertThat(result.errors()).isEmpty();
    }

    private static SkillFile file(String path, String content) {
        return new SkillFile(path, content, "digest");
    }
}
