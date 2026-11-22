package top.yealike.pep.control.agents.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.mockito.ArgumentCaptor;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.McpComponent;
import top.yealike.pep.control.agents.domain.McpRelease;
import top.yealike.pep.control.agents.domain.McpTestReport;
import top.yealike.pep.control.agents.domain.Publication;
import top.yealike.pep.control.agents.mcp.domain.PublishResult;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.runtime.contract.Digests;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.UserIdentity;

class McpServicePublicationTest {

    private static final UserIdentity ACTOR = new UserIdentity("admin", "admin", List.of("ADMIN"), List.of("*"), 1);

    @Test
    void publicationGenerationComesFromMonotonicPublicationStore() {
        UUID mcpId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        Map<String, Object> draft =
                Map.of("endpoint", "https://mcp.example.com/mcp", "timeoutMs", 20000, "contextMode", "OPS");
        McpComponent component = new McpComponent(
                mcpId,
                "custom-ops",
                "运维 MCP",
                "",
                "REMOTE",
                Map.of(),
                draft,
                null,
                3L,
                true,
                5L,
                "admin",
                Instant.now(),
                Instant.now());
        McpToolDescriptor tool =
                new McpToolDescriptor("get_order", "查询订单", Map.of(), Map.of(), Map.of(), "schema", true);
        String configDigest = Digests.digest(Map.of(
                "origin",
                "REMOTE",
                "nativeRegistryRef",
                Map.of(),
                "draft",
                draft,
                "endpoint",
                "https://mcp.example.com/mcp"));

        McpStore store = mock(McpStore.class);
        PublicationStore publications = mock(PublicationStore.class);
        when(store.lock(mcpId)).thenReturn(Optional.of(component));
        when(publications.findByIdempotency("MCP", mcpId, "key")).thenReturn(Optional.empty());
        when(store.findTestReport(reportId))
                .thenReturn(Optional.of(new McpTestReport(
                        reportId, mcpId, 5L, configDigest, Map.of("tools", List.of(tool)), true, Instant.now())));
        when(store.findRelease("custom-ops", "1.0.0")).thenReturn(Optional.empty());
        when(publications.nextGeneration("MCP", mcpId)).thenReturn(7L);
        when(store.insertRelease(
                        eq(mcpId),
                        eq("1.0.0"),
                        anyLong(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(releaseId);
        when(publications.insert(eq("MCP"), eq(mcpId), eq("1.0.0"), eq(5L), anyLong(), eq("key"), any()))
                .thenReturn(publicationId);

        McpService service = new McpService(
                store,
                mock(NacosMcpCatalogService.class),
                mock(McpProbe.class),
                mock(CredentialService.class),
                mock(CredentialStore.class),
                publications);

        service.publish(ACTOR, mcpId, "1.0.0", reportId, 5L, "key");

        ArgumentCaptor<Long> releaseGeneration = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> endpointPolicy = ArgumentCaptor.forClass(String.class);
        verify(store)
                .insertRelease(
                        eq(mcpId),
                        eq("1.0.0"),
                        releaseGeneration.capture(),
                        anyString(),
                        endpointPolicy.capture(),
                        isNull(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString());
        ArgumentCaptor<Long> publicationGeneration = ArgumentCaptor.forClass(Long.class);
        verify(publications)
                .insert(eq("MCP"), eq(mcpId), eq("1.0.0"), eq(5L), publicationGeneration.capture(), eq("key"), any());
        assertThat(releaseGeneration.getValue()).isEqualTo(7L);
        assertThat(publicationGeneration.getValue()).isEqualTo(7L);
        assertThat(endpointPolicy.getValue()).contains("\"contextMode\":\"OPS\"");
    }

    @Test
    void idempotentRetryWinsBeforeRevisionConflict() {
        UUID mcpId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        McpComponent component = new McpComponent(
                mcpId,
                "ops",
                "运维 MCP",
                "",
                "REMOTE",
                Map.of(),
                Map.of("endpoint", "https://mcp.example.com/mcp"),
                "1.0.0",
                3L,
                true,
                9L,
                "admin",
                Instant.now(),
                Instant.now());
        Publication publication = new Publication(
                publicationId,
                "MCP",
                mcpId,
                "1.0.0",
                5L,
                "PUBLISHED",
                "PUBLISHED",
                7L,
                "key",
                null,
                null,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now());
        McpRelease release = new McpRelease(
                releaseId,
                mcpId,
                "ops",
                "1.0.0",
                "STREAMABLE_HTTP",
                Map.of(),
                null,
                List.of(),
                "catalog",
                "content",
                "ACTIVE",
                7L,
                Instant.now());
        McpStore store = mock(McpStore.class);
        PublicationStore publications = mock(PublicationStore.class);
        when(store.lock(mcpId)).thenReturn(Optional.of(component));
        when(publications.findByIdempotency("MCP", mcpId, "key")).thenReturn(Optional.of(publication));
        when(store.findRelease("ops", "1.0.0")).thenReturn(Optional.of(release));

        McpService service = new McpService(
                store,
                mock(NacosMcpCatalogService.class),
                mock(McpProbe.class),
                mock(CredentialService.class),
                mock(CredentialStore.class),
                publications);

        PublishResult result = service.publish(ACTOR, mcpId, null, null, 5L, "key");

        assertThat(result.releaseId()).isEqualTo(releaseId);
        assertThat(result.publicationId()).isEqualTo(publicationId);
        verify(store, never())
                .insertRelease(
                        any(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString());
    }
}
