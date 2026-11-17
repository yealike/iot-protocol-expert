package top.yealike.ipe.ai.platform.mcp;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ai.contract.ComponentDigests;
import top.yealike.ipe.ai.contract.ComponentEndpoint;
import top.yealike.ipe.ai.contract.ComponentNames;
import top.yealike.ipe.ai.contract.ComponentType;
import top.yealike.ipe.ai.contract.Digests;
import top.yealike.ipe.ai.contract.EndpointPolicy;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.contract.McpToolExecutionCategory;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.platform.credential.CredentialService;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpRelease;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpTestReport;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.McpToolPolicy;
import top.yealike.ipe.ai.platform.registry.NacosRegistryService;
import top.yealike.ipe.ai.platform.store.CredentialStore;
import top.yealike.ipe.ai.platform.store.McpStore;
import top.yealike.ipe.ai.platform.store.PublicationStore;
import top.yealike.ipe.ai.support.AiExceptions;
import top.yealike.ipe.ai.support.JsonSupport;

/** MCP 组件的管理、测试、发布与精确版本描述。 */
@Service
public class McpService {

    private final McpStore store;
    private final NacosMcpCatalogService nativeCatalog;
    private final McpProbe probe;
    private final CredentialService credentials;
    private final CredentialStore credentialStore;
    private final NacosRegistryService registry;
    private final PublicationStore publications;

    public McpService(
            McpStore store,
            NacosMcpCatalogService nativeCatalog,
            McpProbe probe,
            CredentialService credentials,
            CredentialStore credentialStore,
            NacosRegistryService registry,
            PublicationStore publications) {
        this.store = store;
        this.nativeCatalog = nativeCatalog;
        this.probe = probe;
        this.credentials = credentials;
        this.credentialStore = credentialStore;
        this.registry = registry;
        this.publications = publications;
    }

    public List<McpComponent> list() {
        return store.list();
    }

    public McpComponent require(UUID id) {
        return store.find(id).orElseThrow(() -> AiExceptions.notFound("MCP 不存在"));
    }

    public List<McpRelease> releases(UUID componentId) {
        require(componentId);
        return store.releases(componentId);
    }

    public McpRelease release(String key, String version) {
        return store.findRelease(key, version).orElseThrow(() -> AiExceptions.notFound("MCP 发布版本不存在"));
    }

    public UUID requireActiveReleaseId(String key, String version) {
        McpRelease release = release(key, version);
        if (!"ACTIVE".equals(release.state())) {
            throw AiExceptions.badRequest("MCP 版本未发布，不能绑定：" + key + "@" + version);
        }
        return release.id();
    }

    public UUID create(
            UserIdentity actor,
            String key,
            String displayName,
            String description,
            String origin,
            Map<String, Object> nativeRegistryRef,
            Map<String, Object> draft) {
        requireKey(key);
        if (store.findByKey(key).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "MCP key 已存在");
        }
        if (!List.of("NATIVE_REGISTRY", "REMOTE").contains(origin)) {
            throw AiExceptions.badRequest("MCP 来源只支持 NATIVE_REGISTRY 或 REMOTE");
        }
        validateConfig(origin, nativeRegistryRef, draft);
        validateCredential(draft);
        return store.insert(
                key,
                displayName,
                description,
                origin,
                JsonSupport.write(nativeRegistryRef == null ? Map.of() : nativeRegistryRef),
                JsonSupport.write(draft == null ? Map.of() : draft),
                actor.username());
    }

    public McpComponent update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            Map<String, Object> nativeRegistryRef,
            Map<String, Object> draft,
            boolean enabled) {
        McpComponent component = require(id);
        validateConfig(component.origin(), nativeRegistryRef, draft);
        validateCredential(draft);
        int updated = store.update(
                id,
                expectedRevision,
                displayName,
                description,
                JsonSupport.write(nativeRegistryRef == null ? Map.of() : nativeRegistryRef),
                JsonSupport.write(draft == null ? Map.of() : draft),
                enabled);
        if (updated == 0) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "MCP 已被其他请求修改，请刷新后重试");
        }
        return require(id);
    }

    @Transactional
    public void delete(UUID id) {
        McpComponent component = store.lock(id).orElseThrow(() -> AiExceptions.notFound("MCP 不存在"));
        if (store.bindingReferences(component.id()) > 0) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "MCP 已被 Agent 发布引用");
        }
        if (!store.releases(component.id()).isEmpty()) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "MCP 已有发布版本，请停用而不是删除");
        }
        store.deleteToolPolicies(component.id());
        store.deleteTestReports(component.id());
        store.delete(component.id());
    }

    public List<McpToolPolicy> toolPolicies(UUID id) {
        require(id);
        return store.toolPolicies(id);
    }

    @Transactional
    public McpToolPolicy reviewTool(UserIdentity actor, UUID id, String toolName, String executionCategory) {
        McpComponent component = store.lock(id).orElseThrow(() -> AiExceptions.notFound("MCP 不存在"));
        if (toolName == null || toolName.isBlank() || toolName.length() > 128) {
            throw AiExceptions.badRequest("MCP 工具名不能为空且不能超过 128 字符");
        }
        McpToolExecutionCategory category = McpToolExecutionCategory.parse(executionCategory);
        if (category == McpToolExecutionCategory.UNKNOWN
                && !"UNKNOWN".equalsIgnoreCase(String.valueOf(executionCategory).strip())) {
            throw AiExceptions.badRequest("工具执行类别只支持 READ_ONLY、WRITE、UNKNOWN");
        }
        if (availableTools(component).stream().noneMatch(tool -> tool.name().equals(toolName))) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "MCP 当前目录中不存在该工具，请先测试");
        }
        store.upsertToolPolicy(component.id(), toolName, category, actor.username());
        return store.toolPolicies(component.id()).stream()
                .filter(policy -> policy.toolName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> AiExceptions.unavailable("MCP 工具审核记录保存失败"));
    }

    public List<McpToolDescriptor> readOnlyTools(McpRelease release) {
        Map<String, McpToolExecutionCategory> policies = store.toolCategories(release.componentId());
        return release.tools().stream()
                .filter(tool -> policies.getOrDefault(tool.name(), McpToolExecutionCategory.UNKNOWN)
                        .readOnly())
                .toList();
    }

    public boolean isReadOnlyApproved(UUID mcpId, String toolName) {
        return store.toolCategories(mcpId)
                .getOrDefault(toolName, McpToolExecutionCategory.UNKNOWN)
                .readOnly();
    }

    public Map<String, Object> test(UUID id) {
        McpComponent component = require(id);
        Resolved resolved = resolve(component);
        String configDigest = configDigest(component, resolved.endpoint());
        long started = System.nanoTime();
        try {
            McpProbe.ProbeResult result =
                    probe.probe(resolved.endpoint(), resolved.authorization(), timeoutMs(component));
            if (result.tools().isEmpty()) {
                throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 未提供任何工具，禁止发布");
            }
            List<String> warnings = nativeWarnings(component, result);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("endpoint", resolved.endpoint());
            report.put("serverName", result.serverName());
            report.put("serverVersion", result.serverVersion());
            report.put("protocolVersion", result.protocolVersion());
            report.put("tools", result.tools());
            report.put("latencyMs", result.latencyMs());
            report.put("warnings", warnings);
            report.put("success", true);
            UUID reportId =
                    store.insertTestReport(id, component.revision(), configDigest, JsonSupport.write(report), true);
            report.put("reportId", reportId);
            report.put("configDigest", configDigest);
            report.put("testedAt", Instant.now());
            return report;
        } catch (AiExceptions.AiException exception) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("endpoint", resolved.endpoint());
            report.put("success", false);
            report.put("errorCode", exception.code());
            report.put("message", exception.getMessage());
            report.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            UUID reportId =
                    store.insertTestReport(id, component.revision(), configDigest, JsonSupport.write(report), false);
            report.put("reportId", reportId);
            throw exception;
        }
    }

    public Map<String, Object> latestTools(UUID id) {
        McpComponent component = require(id);
        Map<String, Object> data = new LinkedHashMap<>();
        store.latestTestReport(id)
                .ifPresentOrElse(
                        report -> {
                            data.put("source", "TEST");
                            data.put("reportId", report.id());
                            data.put("success", report.success());
                            data.put("testedAt", report.createdAt());
                            data.put("tools", report.result().get("tools"));
                            data.put("warnings", report.result().getOrDefault("warnings", List.of()));
                        },
                        () -> data.put("source", "NONE"));
        store.latestActive(id).ifPresent(release -> {
            data.put("publishedVersion", release.version());
            data.put("publishedTools", release.tools());
            data.put("catalogDigest", release.catalogDigest());
        });
        data.put("publishedVersion", component.publishedVersion());
        return data;
    }

    @Transactional
    public PublishResult publish(
            UserIdentity actor, UUID id, String version, UUID reportId, long expectedRevision, String idempotencyKey) {
        McpComponent component = store.lock(id).orElseThrow(() -> AiExceptions.notFound("MCP 不存在"));
        String publicationKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey.strip();
        var existing = publications.findByIdempotency("MCP", id, publicationKey);
        if (existing.isPresent()) {
            if (version != null
                    && !version.isBlank()
                    && !existing.get().version().equals(requireVersion(version))) {
                throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "幂等键已用于不同的 MCP 发布请求");
            }
            McpRelease release = store.findRelease(
                            component.key(), existing.get().version())
                    .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "发布记录与 MCP 版本不一致"));
            return new PublishResult(release.id(), existing.get().id(), release.version(), release.catalogDigest());
        }
        if (component.revision() != expectedRevision) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "MCP 已被其他请求修改，请刷新后重试");
        }
        String resolvedVersion = version == null || version.isBlank()
                ? nextVersion(component.publishedVersion())
                : requireVersion(version);
        McpTestReport report = store.findTestReport(reportId)
                .filter(item -> item.mcpId().equals(id))
                .orElseThrow(() -> AiExceptions.unprocessable("MCP_TEST_REQUIRED", "测试报告不存在或不属于该 MCP"));
        if (!report.success()) {
            throw AiExceptions.unprocessable("MCP_TEST_FAILED", "最近一次测试失败，不能发布");
        }
        Resolved resolved = resolve(component);
        String configDigest = configDigest(component, resolved.endpoint());
        if (!configDigest.equals(report.configDigest())) {
            throw AiExceptions.unprocessable("MCP_TEST_STALE", "配置已变化，请重新测试后再发布");
        }
        List<McpToolDescriptor> tools =
                JsonSupport.readList(JsonSupport.write(report.result().get("tools")), McpToolDescriptor.class);
        if (tools.isEmpty()) {
            throw AiExceptions.unprocessable(ErrorCodes.MCP_INIT_FAILED, "MCP 工具目录为空，禁止发布");
        }
        if (store.findRelease(component.key(), resolvedVersion).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "该 MCP 版本已存在，发布内容不可覆盖");
        }
        long generation = Math.max(component.publicationGeneration() + 1, publications.nextGeneration("MCP", id));
        String catalogDigest = ComponentDigests.mcpCatalog(tools);
        String contentDigest =
                Digests.digest(Map.of("transport", "STREAMABLE_HTTP", "endpoint", resolved.endpoint(), "tools", tools));
        Map<String, Object> endpointPolicy = new LinkedHashMap<>();
        URI resolvedUri = URI.create(resolved.endpoint());
        endpointPolicy.put("endpoint", resolved.endpoint());
        endpointPolicy.put("path", resolvedUri.getRawPath());
        endpointPolicy.put("query", resolvedUri.getRawQuery() == null ? "" : resolvedUri.getRawQuery());
        endpointPolicy.put(
                "serviceName", ComponentNames.serviceName(ComponentType.MCP, component.key(), resolvedVersion));
        endpointPolicy.put("timeoutMs", timeoutMs(component));
        UUID releaseId = store.insertRelease(
                component.id(),
                resolvedVersion,
                generation,
                "STREAMABLE_HTTP",
                JsonSupport.write(endpointPolicy),
                credentialId(component),
                tools,
                catalogDigest,
                contentDigest,
                actor.username());
        UUID publicationId = publications.insert(
                "MCP",
                component.id(),
                resolvedVersion,
                component.revision(),
                generation,
                publicationKey,
                Instant.now().plusSeconds(60));
        publications.insertOutbox(
                "MCP",
                releaseId,
                generation,
                "PUBLISH_REQUESTED",
                JsonSupport.write(
                        Map.of("releaseId", releaseId, "publicationId", publicationId, "version", resolvedVersion)));
        return new PublishResult(releaseId, publicationId, resolvedVersion, catalogDigest);
    }

    public McpDescriptor descriptor(String key, String version) {
        McpRelease release = store.findRelease(key, version)
                .filter(item -> "ACTIVE".equals(item.state()))
                .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.RELEASE_NOT_ACTIVE, "MCP 版本未发布或不可用"));
        String endpoint = resolvePublishedEndpoint(release);
        String credentialRef = null;
        long secretRevision = 0;
        if (release.credentialId() != null) {
            var credential = credentialStore
                    .find(release.credentialId())
                    .orElseThrow(() -> AiExceptions.unavailable("MCP 凭据不存在"));
            credentialRef = credential.key();
            secretRevision = credential.currentSecretRevision();
        }
        Map<String, McpToolExecutionCategory> policies = store.toolCategories(release.componentId());
        List<McpToolDescriptor> tools = release.tools().stream()
                .map(tool -> withReadOnly(
                        tool,
                        policies.getOrDefault(tool.name(), McpToolExecutionCategory.UNKNOWN)
                                .readOnly()))
                .toList();
        return new McpDescriptor(
                release.componentKey(),
                release.version(),
                release.transport(),
                endpoint,
                credentialRef,
                secretRevision,
                release.catalogDigest(),
                tools,
                release.endpointPolicy());
    }

    private List<McpToolDescriptor> availableTools(McpComponent component) {
        return store.latestTestReport(component.id())
                .filter(McpTestReport::success)
                .filter(report -> report.draftRevision() == component.revision())
                .map(report ->
                        JsonSupport.readList(JsonSupport.write(report.result().get("tools")), McpToolDescriptor.class))
                .orElseGet(() -> store.latestActive(component.id())
                        .map(McpRelease::tools)
                        .orElse(List.of()));
    }

    private static McpToolDescriptor withReadOnly(McpToolDescriptor tool, boolean readOnly) {
        if (tool.readOnly() == readOnly) {
            return tool;
        }
        return new McpToolDescriptor(
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                tool.outputSchema(),
                tool.annotations(),
                tool.schemaDigest(),
                readOnly);
    }

    public McpRelease release(UUID releaseId) {
        return store.findRelease(releaseId).orElseThrow(() -> AiExceptions.notFound("MCP 版本不存在"));
    }

    private String resolvePublishedEndpoint(McpRelease release) {
        try {
            String serviceName = text(release.endpointPolicy().get("serviceName"));
            String path = text(release.endpointPolicy().get("path"));
            String query = text(release.endpointPolicy().get("query"));
            String endpoint = text(release.endpointPolicy().get("endpoint"));
            if (registry.enabled() && !serviceName.isBlank()) {
                NacosRegistryService.InstanceView instance = registry.pickHealthy(serviceName);
                if (instance == null) {
                    throw AiExceptions.unprocessable(ErrorCodes.REGISTRY_UNAVAILABLE, "MCP 无健康注册实例");
                }
                String scheme = text(instance.metadata().get("scheme"));
                if (scheme.isBlank()) {
                    scheme = fallbackScheme(endpoint);
                }
                String discoveredPath = text(instance.metadata().get("path"));
                String discoveredQuery = text(instance.metadata().get("query"));
                return ComponentEndpoint.of(
                                scheme,
                                instance.ip(),
                                instance.port(),
                                discoveredPath.isBlank() ? path : discoveredPath,
                                discoveredQuery.isBlank() ? query : discoveredQuery)
                        .toUri();
            }
            return ComponentEndpoint.parse(endpoint).toUri();
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.unprocessable("MCP_ENDPOINT_INVALID", "MCP 入口地址无效：" + release.componentKey());
        }
    }

    private static String fallbackScheme(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "http";
        }
        return ComponentEndpoint.parse(endpoint).scheme();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private Resolved resolve(McpComponent component) {
        if ("NATIVE_REGISTRY".equals(component.origin())) {
            String serverName = String.valueOf(component.nativeRegistryRef().getOrDefault("serverName", ""));
            String namespace = String.valueOf(component.nativeRegistryRef().getOrDefault("namespace", ""));
            String version = String.valueOf(component.nativeRegistryRef().getOrDefault("version", ""));
            NacosMcpCatalogService.CatalogEntry entry = nativeCatalog.fetch(serverName, namespace, version);
            return new Resolved(validatedEndpoint(entry.endpoint()), authorization(component));
        }
        Object endpoint = component.draft().get("endpoint");
        if (!(endpoint instanceof String text)) {
            throw AiExceptions.badRequest("远程 MCP 必须提供 http(s) endpoint");
        }
        return new Resolved(validatedEndpoint(text), authorization(component));
    }

    private String authorization(McpComponent component) {
        UUID credentialId = credentialId(component);
        if (credentialId == null) {
            return null;
        }
        return "Bearer " + credentials.resolve(credentialId, 0);
    }

    private UUID credentialId(McpComponent component) {
        try {
            return credentialId(component.draft());
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.unavailable("MCP 凭据配置无效");
        }
    }

    private static UUID credentialId(Map<String, Object> draft) {
        Object raw = draft == null ? null : draft.get("credentialId");
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("credentialId 必须是 UUID", exception);
            }
        }
        if (raw != null && !(raw instanceof String)) {
            throw new IllegalArgumentException("credentialId 必须是字符串");
        }
        return null;
    }

    private void validateCredential(Map<String, Object> draft) {
        UUID id;
        try {
            id = credentialId(draft);
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("MCP credentialId 必须是 UUID");
        }
        if (id == null) {
            return;
        }
        var credential = credentialStore.find(id).orElseThrow(() -> AiExceptions.badRequest("MCP 凭据不存在"));
        if (!credential.enabled()) {
            throw AiExceptions.badRequest("MCP 凭据已停用");
        }
    }

    private int timeoutMs(McpComponent component) {
        Object raw = component.draft().get("timeoutMs");
        if (raw instanceof Number number) {
            return Math.min(Math.max(number.intValue(), 1000), 20000);
        }
        return 20000;
    }

    private String configDigest(McpComponent component, String endpoint) {
        return Digests.digest(Map.of(
                "origin", component.origin(),
                "nativeRegistryRef", component.nativeRegistryRef(),
                "draft", component.draft(),
                "endpoint", endpoint));
    }

    private List<String> nativeWarnings(McpComponent component, McpProbe.ProbeResult result) {
        if (!"NATIVE_REGISTRY".equals(component.origin())) {
            return List.of();
        }
        try {
            String serverName = String.valueOf(component.nativeRegistryRef().getOrDefault("serverName", ""));
            String namespace = String.valueOf(component.nativeRegistryRef().getOrDefault("namespace", ""));
            String version = String.valueOf(component.nativeRegistryRef().getOrDefault("version", ""));
            var entry = nativeCatalog.fetch(serverName, namespace, version);
            List<String> catalogNames = entry.tools().stream()
                    .map(item -> String.valueOf(item.get("name")))
                    .toList();
            List<String> actualNames =
                    result.tools().stream().map(McpToolDescriptor::name).toList();
            List<String> missing = new ArrayList<>(catalogNames);
            missing.removeAll(actualNames);
            return missing.isEmpty() ? List.of() : List.of("目录工具在运行时缺失：" + String.join(", ", missing));
        } catch (RuntimeException exception) {
            return List.of("无法复核 Nacos 原生目录：" + exception.getMessage());
        }
    }

    private static void validateConfig(
            String origin, Map<String, Object> nativeRegistryRef, Map<String, Object> draft) {
        if ("NATIVE_REGISTRY".equals(origin)) {
            if (nativeRegistryRef == null || nativeRegistryRef.get("serverName") == null) {
                throw AiExceptions.badRequest("原生目录导入必须指定 serverName");
            }
        } else if ("REMOTE".equals(origin)) {
            if (draft == null || !(draft.get("endpoint") instanceof String endpoint)) {
                throw AiExceptions.badRequest("远程 MCP 必须提供 endpoint");
            }
            validatedEndpoint(endpoint);
        } else {
            throw AiExceptions.badRequest("MCP 来源只支持 NATIVE_REGISTRY 或 REMOTE");
        }
    }

    static String validatedEndpoint(String endpoint) {
        try {
            return EndpointPolicy.requireAllowed(endpoint).toString();
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("MCP endpoint " + exception.getMessage());
        }
    }

    static void requireKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw AiExceptions.badRequest("MCP key 只允许 2-40 位小写字母、数字和横线");
        }
    }

    private static String requireVersion(String version) {
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw AiExceptions.badRequest("版本必须为 x.y.z");
        }
        return version;
    }

    private static String nextVersion(String current) {
        if (current == null || !current.matches("\\d+\\.\\d+\\.\\d+")) {
            return "1.0.0";
        }
        String[] parts = current.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    public record PublishResult(UUID releaseId, UUID publicationId, String version, String catalogDigest) {}

    private record Resolved(String endpoint, String authorization) {}
}
