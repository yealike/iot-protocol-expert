package top.yealike.pep.control.agents.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.registry.NacosRegistryService;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.McpToolExecutionCategory;
import top.yealike.pep.runtime.contract.UserIdentity;

class McpServiceToolPolicyTest {

    private final McpStore store = mock(McpStore.class);
    private final NacosRegistryService registry = mock(NacosRegistryService.class);
    private final McpService service = new McpService(
            store,
            mock(NacosMcpCatalogService.class),
            mock(McpProbe.class),
            mock(CredentialService.class),
            mock(CredentialStore.class),
            registry,
            mock(PublicationStore.class));

    @Test
    void descriptorOverridesUntrustedProviderReadOnlyHint() {
        UUID componentId = UUID.randomUUID();
        McpToolDescriptor providerTool = new McpToolDescriptor(
                "get_device", "", Map.of("type", "object"), Map.of(), Map.of("readOnlyHint", true), "schema", true);
        McpRelease release = new McpRelease(
                UUID.randomUUID(),
                componentId,
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                Map.of("endpoint", "http://127.0.0.1:19090/mcp"),
                null,
                List.of(providerTool),
                "catalog",
                "content",
                "ACTIVE",
                1L,
                Instant.now());
        when(store.findRelease("ops", "1.0.0")).thenReturn(Optional.of(release));
        when(store.toolCategories(componentId)).thenReturn(Map.of("get_device", McpToolExecutionCategory.WRITE));
        when(registry.enabled()).thenReturn(false);

        McpDescriptor descriptor = service.descriptor("ops", "1.0.0");

        assertThat(descriptor.tools())
                .singleElement()
                .extracting(McpToolDescriptor::readOnly)
                .isEqualTo(false);
    }

    @Test
    void reviewRejectsUnknownCategoryWithoutWritingPolicy() {
        UUID componentId = UUID.randomUUID();
        McpComponent component = new McpComponent(
                componentId,
                "ops",
                "ops",
                "",
                "REMOTE",
                Map.of(),
                Map.of(),
                null,
                0L,
                true,
                0L,
                "admin",
                Instant.now(),
                Instant.now());
        when(store.lock(componentId)).thenReturn(Optional.of(component));

        UserIdentity actor = new UserIdentity("admin", "admin", List.of("ADMIN"), List.of(), 1L);
        assertThatThrownBy(() -> service.reviewTool(actor, componentId, "get_device", "EXECUTE"))
                .isInstanceOf(AiExceptions.AiException.class)
                .hasMessageContaining("READ_ONLY");
        verify(store, never())
                .upsertToolPolicy(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
