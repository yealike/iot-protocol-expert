package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManifestDigestsTest {
    @Test
    void existingV1ReleaseRetainsItsStoredDigest() {
        assertThat(ManifestDigests.digest(manifest(0.1)))
                .isEqualTo("99f6a9ef9c97e365c1caea0d69a4f696b0511b53fc65a7264555bd22060aef41");
    }

    @Test
    void v2DigestCoversFrozenTargetAndAllowedToolScope() {
        var target = UUID.randomUUID();
        var binding = new DelegateBinding(
                "domain",
                "specialist",
                target,
                "1.0.0",
                "digest",
                "匹配协议版本",
                null,
                List.of("lookup"),
                true,
                2,
                30_000,
                0);
        var changed = new DelegateBinding(
                "domain",
                "specialist",
                target,
                "1.0.0",
                "digest",
                "匹配协议版本",
                null,
                List.of("lookup", "details"),
                true,
                2,
                30_000,
                0);

        assertThat(ManifestDigests.digest(v2(binding))).isNotEqualTo(ManifestDigests.digest(v2(changed)));
        var delegates = (List<?>) ManifestDigests.canonical(v2(binding)).get("delegates");
        assertThat(((Map<?, ?>) delegates.getFirst()).get("targetReleaseId")).isEqualTo(target.toString());
    }

    private static AgentManifest v2(DelegateBinding binding) {
        var base = manifest(0.1);
        return new AgentManifest(
                AgentManifest.V2,
                base.agentKey(),
                base.version(),
                base.displayName(),
                base.systemPrompt(),
                base.model(),
                base.skills(),
                base.mcp(),
                base.budget(),
                base.executionPolicy(),
                "充电协议专家",
                List.of("ocpp"),
                List.of(),
                List.of(binding),
                DelegationPolicy.defaults(120_000));
    }

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
