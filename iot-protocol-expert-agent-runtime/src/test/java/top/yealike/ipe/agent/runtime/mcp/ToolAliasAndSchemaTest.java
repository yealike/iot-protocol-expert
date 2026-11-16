package top.yealike.ipe.agent.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.agent.runtime.TestFixtures;
import top.yealike.ipe.agent.runtime.prepare.PreparedTool;
import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.contract.ToolAliasCodec;

class ToolAliasAndSchemaTest {

    @Test
    void buildsStableAliasAndHonorsLengthLimit() {
        assertThat(ToolAliasCodec.alias("ops", "get_order_detail")).isEqualTo("mcp_ops__get_order_detail");
        String longName = ToolAliasCodec.alias("very-long-mcp-key", "a".repeat(100));
        assertThat(longName).hasSizeLessThanOrEqualTo(64);
        assertThat(longName).startsWith("mcp_very-long-mcp-key__");
    }

    @Test
    void privateContextFieldIsNeverExposedToModel() {
        McpToolDescriptor descriptor = TestFixtures.mcpTool();
        PreparedTool tool = new PreparedTool("ops_get_order_detail", "ops", "1.0.0", descriptor.name(), descriptor);
        Map<String, Object> schema = tool.parametersForModel();
        assertThat(schema.get("properties")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) schema.get("properties")).containsKey("_ipe_context"))
                .isFalse();
        assertThat(schema.get("required")).isEqualTo(List.of("order_no"));
    }
}
