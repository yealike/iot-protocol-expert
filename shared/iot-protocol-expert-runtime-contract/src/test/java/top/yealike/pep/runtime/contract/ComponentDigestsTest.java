package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentDigestsTest {

    @Test
    void mcpCatalogIsIndependentOfInputOrder() {
        McpToolDescriptor first =
                new McpToolDescriptor("a", "", Map.of("type", "object"), Map.of(), Map.of(), "d1", true);
        McpToolDescriptor second =
                new McpToolDescriptor("b", "", Map.of("type", "object"), Map.of(), Map.of(), "d2", true);
        assertThat(ComponentDigests.mcpCatalog(List.of(first, second)))
                .isEqualTo(ComponentDigests.mcpCatalog(List.of(second, first)));
    }

    @Test
    void skillBundleUsesPathOrder() {
        SkillFile a = new SkillFile("SKILL.md", "a", Digests.sha256Hex("a"));
        SkillFile b = new SkillFile("references/b.md", "b", Digests.sha256Hex("b"));
        SkillBundle one = new SkillBundle("s", "1.0.0", "s", "d", "x", List.of(a, b));
        SkillBundle two = new SkillBundle("s", "1.0.0", "s", "d", "x", List.of(b, a));
        assertThat(ComponentDigests.skillBundle(one)).isEqualTo(ComponentDigests.skillBundle(two));
    }
}
