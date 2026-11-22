package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.config.RuntimeProperties;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.SkillBundle;
import top.yealike.pep.runtime.contract.SkillFile;
import top.yealike.pep.runtime.prepare.PreparedRelease;

class PromptAssemblerTest {

    @Test
    void alwaysSkillContentIsTruncatedToConfiguredLimit() {
        RuntimeProperties properties = new RuntimeProperties(
                0, null, null, null, null, null, null, null, null, null, null, new RuntimeProperties.Skills(20), null);
        AgentManifest manifest = new AgentManifest(
                "v1",
                "protocol-expert",
                "1.0.0",
                "Agent",
                "system",
                null,
                List.of(new AgentManifest.SkillBinding("demo", "1.0.0", "digest", "ALWAYS", 0)),
                List.of(),
                null,
                null);
        SkillBundle bundle = new SkillBundle(
                "demo",
                "1.0.0",
                "Demo",
                "description",
                "digest",
                List.of(new SkillFile("SKILL.md", "x".repeat(100), "digest")));
        PreparedRelease release = new PreparedRelease(
                UUID.randomUUID(),
                "manifest-digest",
                1,
                UUID.randomUUID(),
                manifest,
                Map.of(),
                Map.of("demo", bundle),
                List.of(),
                List.of(),
                Instant.now());

        String prompt = new PromptAssembler(properties).assemble(release);

        assertThat(prompt).contains("x".repeat(20)).doesNotContain("x".repeat(21));
        assertThat(prompt).contains("Skill 正文已截断");
    }
}
