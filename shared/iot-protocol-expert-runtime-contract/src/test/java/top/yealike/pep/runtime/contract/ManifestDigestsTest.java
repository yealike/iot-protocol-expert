package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestDigestsTest {

    @Test
    void digestIsStableAcrossEquivalentManifests() {
        AgentManifest one = manifest(new BigDecimal("0.10"));
        AgentManifest two = manifest(new BigDecimal("0.1"));
        assertThat(ManifestDigests.digest(one)).isEqualTo(ManifestDigests.digest(two));
    }

    private static AgentManifest manifest(Object temperature) {
        AgentManifest.ModelSpec model = new AgentManifest.ModelSpec(
                "default",
                "OPENAI_COMPAT",
                "https://api.deepseek.com",
                "deepseek-flash",
                "model-default",
                1,
                Map.of("temperature", temperature));
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
