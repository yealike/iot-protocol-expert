package top.yealike.pep.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentDigestsTest {

    @Test
    void acceptsReorderedRequiredFieldsButChecksTheRestOfTheFrozenSchema() {
        var properties = Map.of(
                "_ipe_context", Map.of("type", "string"),
                "offset", Map.of("type", "integer"),
                "id", Map.of("type", "integer"));
        Map<String, Object> frozenSchema =
                Map.of("properties", properties, "required", List.of("_ipe_context", "offset", "id"));
        Map<String, Object> reordered =
                Map.of("properties", properties, "required", List.of("offset", "id", "_ipe_context"));
        var frozen = new McpToolDescriptor(
                "read_document", "", frozenSchema, Map.of(), Map.of(), McpToolSchemas.digest(frozenSchema), true);

        assertThat(McpToolSchemas.digest(reordered)).isNotEqualTo(frozen.schemaDigest());
        assertThat(McpToolSchemas.matchesFrozen(reordered, frozen)).isTrue();
        assertThat(McpToolSchemas.matchesFrozen(
                        Map.of("properties", properties, "required", List.of("id", "_ipe_context")), frozen))
                .isFalse();
        var changedType = new java.util.LinkedHashMap<>(properties);
        changedType.put("offset", Map.of("type", "string"));
        assertThat(McpToolSchemas.matchesFrozen(
                        Map.of("properties", changedType, "required", List.of("offset", "id", "_ipe_context")), frozen))
                .isFalse();
    }

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
