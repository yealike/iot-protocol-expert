package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestDigestsTest {

    @Test
    void digestIsStableAcrossEquivalentManifests() {
        AgentManifest one = manifest(0.10);
        AgentManifest two = manifest(0.1);
        assertThat(ManifestDigests.digest(one)).isEqualTo(ManifestDigests.digest(two));
    }

    @Test
    void canonicalManifestUsesTheStoredModelOptionsMapShape() {
        Map<?, ?> canonicalModel =
                (Map<?, ?>) ManifestDigests.canonical(manifest(0.1)).get("model");

        assertThat(canonicalModel.get("options")).isEqualTo(Map.of("temperature", 0.1, "maxTokens", 1024));
    }

    private static AgentManifest manifest(double temperature) {
        AgentManifest.ModelSpec model = new AgentManifest.ModelSpec(
                "default",
                "OPENAI_COMPAT",
                "https://api.deepseek.com",
                "deepseek-flash",
                "model-default",
                1,
                new ModelOptions(temperature, 1024, null));
        return new AgentManifest(
                "ipe.agent/v1",
                "protocol-expert",
                "1.0.0",
                "协议专家",
                "prompt",
                model,
                List.of(new AgentManifest.SkillBinding("s", "1.0.0", "d", "AUTO", 10)),
                List.of(),
                new AgentManifest.Budget(6, 12, 120000),
                new AgentManifest.ExecutionPolicy(false, "DENY"));
    }
}
