package top.yealike.pep.control.agents.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

class McpToolCatalogTest {

    @Test
    void readsEveryPageAndMapsTools() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(page(List.of(tool("a")), "next"));
        when(client.listTools("next")).thenReturn(page(List.of(tool("b")), null));

        List<McpToolDescriptor> tools = McpToolCatalog.readAll(client, Long.MAX_VALUE);

        assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("a", "b");
        verify(client).listTools("next");
    }

    @Test
    void rejectsRepeatedCursor() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(page(List.of(tool("a")), "same"));
        when(client.listTools("same")).thenReturn(page(List.of(tool("b")), "same"));

        assertThatThrownBy(() -> McpToolCatalog.readAll(client, Long.MAX_VALUE))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("游标重复");
    }

    @Test
    void rejectsDuplicateToolNames() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(page(List.of(tool("a"), tool("a")), null));

        assertThatThrownBy(() -> McpToolCatalog.readAll(client, Long.MAX_VALUE))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("工具名称重复");
    }

    @Test
    void rejectsMoreThanTwoHundredTools() {
        McpSyncClient client = mock(McpSyncClient.class);
        List<McpSchema.Tool> tools = new ArrayList<>();
        for (int index = 0; index <= McpToolCatalog.MAX_TOOLS; index++) {
            tools.add(tool("tool-" + index));
        }
        when(client.listTools()).thenReturn(page(tools, null));

        assertThatThrownBy(() -> McpToolCatalog.readAll(client, Long.MAX_VALUE))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("超过 200");
    }

    @Test
    void rejectsMoreThanTwentyPages() {
        McpSyncClient client = mock(McpSyncClient.class);
        AtomicInteger page = new AtomicInteger();
        when(client.listTools()).thenReturn(page(List.of(tool("tool-0")), "cursor-1"));
        when(client.listTools(anyString())).thenAnswer(invocation -> {
            int current = page.incrementAndGet();
            return page(List.of(tool("tool-" + current)), "cursor-" + (current + 1));
        });

        assertThatThrownBy(() -> McpToolCatalog.readAll(client, Long.MAX_VALUE))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("超过 20 页");
    }

    @Test
    void stopsBeforeListingWhenDeadlineHasPassed() {
        McpSyncClient client = mock(McpSyncClient.class);

        assertThatThrownBy(() -> McpToolCatalog.readAll(client, System.nanoTime() - 1))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("超时");
        verify(client, never()).listTools();
    }

    @Test
    void treatsMissingToolListAsEmptyPage() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(null, null));

        assertThat(McpToolCatalog.readAll(client, Long.MAX_VALUE)).isEmpty();
    }

    private static McpSchema.ListToolsResult page(List<McpSchema.Tool> tools, String nextCursor) {
        return new McpSchema.ListToolsResult(tools, nextCursor);
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(
                name,
                null,
                "description",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null),
                null,
                null,
                null);
    }
}
