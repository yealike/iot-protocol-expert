package top.yealike.ipe.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DigestsTest {

    @Test
    void keyOrderAndTrailingZeroDoNotChangeDigest() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 0.10);
        first.put("a", List.of("x", 0.1));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", List.of("x", 0.10));
        second.put("b", 0.1);
        assertThat(Digests.digest(first)).isEqualTo(Digests.digest(second));
    }

    @Test
    void aliasIsStableAndBounded() {
        String alias = ToolAliasCodec.alias("ops", "get_device_detail");
        assertThat(alias).isEqualTo("mcp_ops__get_device_detail");
        String longAlias = ToolAliasCodec.alias("ops", "x".repeat(120));
        assertThat(longAlias).hasSizeLessThanOrEqualTo(64);
        assertThat(longAlias).startsWith("mcp_ops__");
    }

    @Test
    void serviceNameMatchesDesignExample() {
        assertThat(ComponentNames.serviceName(ComponentType.MCP, "ops", "1.0.0"))
                .isEqualTo("ipe-mcp--ops--v1-0-0");
    }
}
