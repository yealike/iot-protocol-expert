package top.yealike.ipe.ai.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpTestReport;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpToolPolicy;

public interface McpPlatformMapper {

    List<McpComponent> listComponents();

    List<McpComponent> findComponent(@Param("id") UUID id);

    List<McpComponent> findComponentByKey(@Param("key") String key);

    List<McpComponent> lockComponent(@Param("id") UUID id);

    int insertComponent(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("origin") String origin,
            @Param("nativeRegistryRefJson") String nativeRegistryRefJson,
            @Param("draftJson") String draftJson,
            @Param("actor") String actor);

    int updateComponent(
            @Param("id") UUID id,
            @Param("expectedRevision") long expectedRevision,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("nativeRegistryRefJson") String nativeRegistryRefJson,
            @Param("draftJson") String draftJson,
            @Param("enabled") boolean enabled);

    int setComponentEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    int deleteComponent(@Param("id") UUID id);

    List<McpToolPolicy> toolPolicies(@Param("mcpId") UUID mcpId);

    int upsertToolPolicy(
            @Param("mcpId") UUID mcpId,
            @Param("toolName") String toolName,
            @Param("executionCategory") String executionCategory,
            @Param("reviewedBy") String reviewedBy);

    int deleteToolPolicies(@Param("mcpId") UUID mcpId);

    int deleteTestReports(@Param("mcpId") UUID mcpId);

    long bindingReferences(@Param("componentId") UUID componentId);

    int insertRelease(
            @Param("id") UUID id,
            @Param("componentId") UUID componentId,
            @Param("version") String version,
            @Param("generation") long generation,
            @Param("transport") String transport,
            @Param("endpointPolicyJson") String endpointPolicyJson,
            @Param("credentialId") UUID credentialId,
            @Param("toolCatalogJson") String toolCatalogJson,
            @Param("catalogDigest") String catalogDigest,
            @Param("contentDigest") String contentDigest,
            @Param("actor") String actor);

    List<McpRelease> findRelease(@Param("id") UUID id);

    List<McpRelease> findReleaseByKey(@Param("key") String key, @Param("version") String version);

    List<McpRelease> latestActiveRelease(@Param("componentId") UUID componentId);

    List<McpRelease> releases(@Param("componentId") UUID componentId);

    int setReleaseState(@Param("releaseId") UUID releaseId, @Param("state") String state);

    int publishComponent(
            @Param("componentId") UUID componentId,
            @Param("version") String version,
            @Param("expectedGeneration") long expectedGeneration);

    int countPublished(
            @Param("componentId") UUID componentId,
            @Param("version") String version,
            @Param("expectedGeneration") long expectedGeneration);

    int insertTestReport(
            @Param("id") UUID id,
            @Param("mcpId") UUID mcpId,
            @Param("draftRevision") long draftRevision,
            @Param("configDigest") String configDigest,
            @Param("resultJson") String resultJson,
            @Param("success") boolean success);

    List<McpTestReport> findTestReport(@Param("reportId") UUID reportId);

    List<McpTestReport> latestTestReport(@Param("mcpId") UUID mcpId);
}
