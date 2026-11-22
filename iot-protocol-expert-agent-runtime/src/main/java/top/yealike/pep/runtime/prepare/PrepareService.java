package top.yealike.pep.runtime.prepare;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.yealike.pep.runtime.config.RuntimeProperties;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.ComponentDigests;
import top.yealike.pep.runtime.contract.CredentialResolveRequest;
import top.yealike.pep.runtime.contract.CredentialResolveResponse;
import top.yealike.pep.runtime.contract.Digests;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.ManifestDigests;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.ReleasePrepareRequest;
import top.yealike.pep.runtime.contract.ReleasePrepareResponse;
import top.yealike.pep.runtime.contract.SkillBundle;
import top.yealike.pep.runtime.contract.SkillDescriptor;
import top.yealike.pep.runtime.contract.SkillFile;
import top.yealike.pep.runtime.contract.SkillFileInfo;
import top.yealike.pep.runtime.contract.ToolAliasCodec;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.mcp.McpConnectionPool;
import top.yealike.pep.runtime.registry.NacosRuntimeRegistry;
import top.yealike.pep.runtime.skill.SkillPathGuard;

/**
 * 发布准备：在返回 READY 之前证明 MCP 与 Skill 依赖可发现，并冻结本次运行计划。
 *
 * <p>Runtime 不读取 Control 数据库；所有内容均经内部签名接口获取，并在内存中校验摘要。
 */
@Service
public class PrepareService {

    private static final Logger log = LoggerFactory.getLogger(PrepareService.class);
    private static final int MAX_MCP_TOOLS = 32;
    private static final int MAX_SKILL_FILES = 50;
    private static final long MAX_SKILL_FILE_BYTES = 256L * 1024L;
    private static final long MAX_SKILL_BUNDLE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_ALWAYS_SKILLS = 2;
    private static final Set<String> RESERVED_ALIASES = Set.of("load_skill", "read_skill_resource");

    private final ControlClient control;
    private final McpConnectionPool mcpPool;
    private final PreparedReleaseRegistry releases;
    private final NacosRuntimeRegistry nacos;
    private final RuntimeProperties properties;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public PrepareService(
            ControlClient control,
            McpConnectionPool mcpPool,
            PreparedReleaseRegistry releases,
            NacosRuntimeRegistry nacos,
            RuntimeProperties properties) {
        this.control = control;
        this.mcpPool = mcpPool;
        this.releases = releases;
        this.nacos = nacos;
        this.properties = properties;
    }

    public ReleasePrepareResponse prepare(ReleasePrepareRequest request) {
        requireRequest(request);
        Object lock = locks.computeIfAbsent(request.releaseId(), ignored -> new Object());
        synchronized (lock) {
            PreparedRelease existing = releases.find(request.releaseId()).orElse(null);
            if (existing != null) {
                if (existing.manifestDigest().equalsIgnoreCase(request.manifestDigest())
                        && existing.generation() == request.generation()) {
                    return new ReleasePrepareResponse(
                            "READY",
                            nacos.instanceId(),
                            existing.manifestDigest(),
                            warningMessage(existing.warnings()));
                }
                throw new PrepareException(
                        409, ErrorCodes.REVISION_CONFLICT, "同一 releaseId 的 manifestDigest 或 generation 与已准备版本不一致");
            }

            AgentManifest manifest = control.manifest(request.releaseId());
            if (manifest == null) {
                throw new PrepareException(422, ErrorCodes.MISSING_DEPENDENCY, "发布清单不存在");
            }
            String actualDigest = ManifestDigests.digest(manifest);
            if (!actualDigest.equalsIgnoreCase(request.manifestDigest())) {
                throw new PrepareException(422, "MANIFEST_DIGEST_MISMATCH", "发布清单摘要与派发摘要不一致");
            }
            validateManifest(manifest);

            List<String> warnings = new ArrayList<>();
            Map<String, McpDescriptor> mcps = new LinkedHashMap<>();
            List<PreparedTool> tools = new ArrayList<>();
            try {
                prepareMcps(manifest, request.publicationId(), mcps, tools, warnings);
            } catch (PrepareException exception) {
                if (ErrorCodes.SCHEMA_DRIFT.equals(exception.code())) {
                    releases.recordSchemaFailure(request.releaseId(), actualDigest, exception.getMessage());
                }
                throw exception;
            }
            Set<String> aliases = new HashSet<>();
            for (PreparedTool tool : tools) {
                if (RESERVED_ALIASES.contains(tool.alias().toLowerCase(Locale.ROOT))
                        || !aliases.add(tool.alias().toLowerCase(Locale.ROOT))) {
                    throw new PrepareException(422, "TOOL_ALIAS_CONFLICT", "工具别名冲突：" + tool.alias());
                }
            }
            int skillToolCount = manifest.skills().isEmpty() ? 0 : 2;
            if (tools.size() + skillToolCount > MAX_MCP_TOOLS) {
                throw new PrepareException(
                        422, "TOOL_CATALOG_TOO_LARGE", "模型可见工具数量超过 32 个（含 load_skill/read_skill_resource）");
            }

            Map<String, SkillBundle> skills = new LinkedHashMap<>();
            prepareSkills(manifest, skills);

            PreparedRelease release = new PreparedRelease(
                    request.releaseId(),
                    actualDigest,
                    request.generation(),
                    request.publicationId(),
                    manifest,
                    mcps,
                    skills,
                    tools,
                    warnings,
                    Instant.now());
            releases.put(release);
            log.info(
                    "release prepared: release={} agent={} version={} digest={} tools={} skills={} warnings={}",
                    release.releaseId(),
                    manifest.agentKey(),
                    manifest.version(),
                    release.manifestDigest(),
                    release.tools().size(),
                    release.skills().size(),
                    release.warnings().size());
            return new ReleasePrepareResponse("READY", nacos.instanceId(), actualDigest, warningMessage(warnings));
        }
    }

    private void prepareMcps(
            AgentManifest manifest,
            UUID publicationId,
            Map<String, McpDescriptor> preparedMcps,
            List<PreparedTool> tools,
            List<String> warnings) {
        Set<String> seen = new HashSet<>();
        for (AgentManifest.McpBinding binding : manifest.mcp()) {
            if (blank(binding.key())
                    || blank(binding.version())
                    || !seen.add(binding.key().toLowerCase(Locale.ROOT))) {
                throw new PrepareException(422, "INVALID_MANIFEST", "MCP 绑定 key 重复或为空");
            }
            try {
                McpDescriptor descriptor = control.mcpDescriptor(binding.key(), binding.version());
                validateDescriptor(binding, descriptor);
                String secret = prepareCredential(descriptor, publicationId);
                mcpPool.verify(descriptor, secret);
                List<PreparedTool> bindingTools = new ArrayList<>();
                for (AgentManifest.McpToolBinding toolBinding : binding.tools()) {
                    McpToolDescriptor descriptorTool = descriptor.tools().stream()
                            .filter(tool -> tool.name().equals(toolBinding.name()))
                            .findFirst()
                            .orElseThrow(() -> new PrepareException(
                                    422,
                                    ErrorCodes.SCHEMA_DRIFT,
                                    "MCP 工具未在冻结目录中：" + binding.key() + "." + toolBinding.name()));
                    if (blank(toolBinding.schemaDigest())
                            || blank(descriptorTool.schemaDigest())
                            || !descriptorTool.schemaDigest().equalsIgnoreCase(toolBinding.schemaDigest())) {
                        throw new PrepareException(
                                422,
                                ErrorCodes.SCHEMA_DRIFT,
                                "Manifest 工具摘要与 MCP 冻结目录不一致：" + binding.key() + "." + toolBinding.name());
                    }
                    String alias = alias(toolBinding, binding.key());
                    bindingTools.add(new PreparedTool(
                            alias, binding.key(), binding.version(), toolBinding.name(), descriptorTool));
                }
                // 只有整个 MCP 绑定全部校验通过后才提交，避免 optional MCP 部分工具残留。
                preparedMcps.put(binding.key(), descriptor);
                tools.addAll(bindingTools);
            } catch (RuntimeException exception) {
                if (binding.required()) {
                    throw normalizeDependencyFailure(exception, "MCP 准备失败：" + binding.key());
                }
                String warning = "可选 MCP 不可用，已从工具集合移除："
                        + binding.key()
                        + "/"
                        + binding.version()
                        + "（"
                        + safeReason(exception)
                        + "）";
                warnings.add(warning);
                log.warn(
                        "optional mcp omitted: mcp={} version={} reason={}",
                        binding.key(),
                        binding.version(),
                        safeReason(exception));
            }
        }
    }

    private void prepareSkills(AgentManifest manifest, Map<String, SkillBundle> preparedSkills) {
        int alwaysMode = 0;
        Set<String> seen = new HashSet<>();
        for (AgentManifest.SkillBinding binding : manifest.skills()) {
            if (blank(binding.key())
                    || blank(binding.version())
                    || blank(binding.digest())
                    || !seen.add(binding.key().toLowerCase(Locale.ROOT))) {
                throw new PrepareException(422, "INVALID_MANIFEST", "Skill 绑定 key 重复或摘要为空");
            }
            String loadMode =
                    binding.loadMode() == null ? "AUTO" : binding.loadMode().toUpperCase(Locale.ROOT);
            if (!"AUTO".equals(loadMode) && !"ALWAYS".equals(loadMode)) {
                throw new PrepareException(422, "INVALID_MANIFEST", "Skill loadMode 只允许 AUTO 或 ALWAYS");
            }
            if ("ALWAYS".equals(loadMode) && ++alwaysMode > MAX_ALWAYS_SKILLS) {
                throw new PrepareException(422, "SKILL_BUDGET_EXCEEDED", "ALWAYS 模式 Skill 最多 2 个");
            }
            SkillBundle bundle = loadSkill(binding);
            String digest = ComponentDigests.skillBundle(bundle);
            if (!digest.equalsIgnoreCase(binding.digest())) {
                throw new PrepareException(422, "SKILL_DIGEST_MISMATCH", "Skill 包摘要不一致：" + binding.key());
            }
            preparedSkills.put(binding.key(), bundle);
        }
    }

    private SkillBundle loadSkill(AgentManifest.SkillBinding binding) {
        SkillDescriptor descriptor = control.skillDescriptor(binding.key(), binding.version());
        if (descriptor == null) {
            throw new PrepareException(422, ErrorCodes.MISSING_DEPENDENCY, "Skill 版本不存在：" + binding.key());
        }
        if (!binding.key().equals(descriptor.key()) || !binding.version().equals(descriptor.version())) {
            throw new PrepareException(422, ErrorCodes.MISSING_DEPENDENCY, "Skill 版本定位不一致：" + binding.key());
        }
        if (descriptor.files().size() > MAX_SKILL_FILES) {
            throw new PrepareException(422, "SKILL_BUDGET_EXCEEDED", "Skill 文件数量超过 50 个");
        }
        if (descriptor.digest() != null
                && !descriptor.digest().isBlank()
                && !descriptor.digest().equalsIgnoreCase(binding.digest())) {
            throw new PrepareException(422, "SKILL_DIGEST_MISMATCH", "Skill 描述摘要与 Manifest 不一致");
        }
        List<SkillFile> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long totalBytes = 0;
        boolean hasEntry = false;
        for (SkillFileInfo info : descriptor.files()) {
            String path;
            try {
                path = SkillPathGuard.requireSafe(info.path());
            } catch (IllegalArgumentException exception) {
                throw new PrepareException(422, ErrorCodes.SKILL_PATH_DENIED, exception.getMessage());
            }
            if (!paths.add(path.toLowerCase(Locale.ROOT))) {
                throw new PrepareException(422, ErrorCodes.SKILL_PATH_DENIED, "Skill 路径大小写折叠后冲突");
            }
            if (info.size() > MAX_SKILL_FILE_BYTES) {
                throw new PrepareException(422, "SKILL_BUDGET_EXCEEDED", "Skill 单文件超过 256 KiB");
            }
            SkillFile file = control.skillFile(binding.key(), binding.version(), path);
            if (file == null || file.content() == null) {
                throw new PrepareException(422, ErrorCodes.MISSING_DEPENDENCY, "Skill 文件内容缺失");
            }
            String actual = Digests.sha256Hex(file.content());
            if (!actual.equalsIgnoreCase(info.digest())
                    || (file.digest() != null && !file.digest().isBlank() && !actual.equalsIgnoreCase(file.digest()))) {
                throw new PrepareException(422, "SKILL_DIGEST_MISMATCH", "Skill 文件摘要不一致：" + path);
            }
            long bytes = file.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_SKILL_FILE_BYTES) {
                throw new PrepareException(422, "SKILL_BUDGET_EXCEEDED", "Skill 单文件超过 256 KiB");
            }
            totalBytes += bytes;
            if (totalBytes > MAX_SKILL_BUNDLE_BYTES) {
                throw new PrepareException(422, "SKILL_BUDGET_EXCEEDED", "Skill 包超过 2 MiB");
            }
            if ("skill.md".equals(path.toLowerCase(Locale.ROOT))) {
                hasEntry = true;
            }
            files.add(new SkillFile(path, file.content(), actual));
        }
        if (!hasEntry) {
            throw new PrepareException(422, ErrorCodes.MISSING_DEPENDENCY, "Skill 缺少 SKILL.md");
        }
        return new SkillBundle(
                descriptor.key(),
                descriptor.version(),
                descriptor.name(),
                descriptor.description(),
                binding.digest(),
                files);
    }

    private String prepareCredential(McpDescriptor descriptor, UUID publicationId) {
        if (descriptor.credentialRef() == null || descriptor.credentialRef().isBlank()) {
            return null;
        }
        CredentialResolveResponse response = control.resolveCredential(new CredentialResolveRequest(
                null, 0L, descriptor.credentialRef(), descriptor.secretRevision(), "PREPARE", publicationId));
        return response == null ? null : response.secret();
    }

    private void validateDescriptor(AgentManifest.McpBinding binding, McpDescriptor descriptor) {
        if (descriptor == null
                || !binding.key().equals(descriptor.key())
                || !binding.version().equals(descriptor.version())) {
            throw new PrepareException(422, ErrorCodes.MISSING_DEPENDENCY, "MCP 版本定位不一致：" + binding.key());
        }
        if (!"STREAMABLE_HTTP".equalsIgnoreCase(descriptor.transport())) {
            throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "一期只支持 Streamable HTTP MCP");
        }
        URI endpoint;
        try {
            endpoint = URI.create(descriptor.endpoint());
        } catch (IllegalArgumentException exception) {
            throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP endpoint 格式非法");
        }
        if (endpoint.getScheme() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP endpoint 只允许受控 HTTP(S) 地址");
        }
        if (blank(binding.catalogDigest())
                || blank(descriptor.catalogDigest())
                || !binding.catalogDigest().equalsIgnoreCase(descriptor.catalogDigest())) {
            throw new PrepareException(422, ErrorCodes.SCHEMA_DRIFT, "Manifest 与 MCP 冻结目录摘要不一致");
        }
        String actualCatalog = ComponentDigests.mcpCatalog(descriptor.tools());
        if (descriptor.catalogDigest() != null
                && !descriptor.catalogDigest().isBlank()
                && !actualCatalog.equalsIgnoreCase(descriptor.catalogDigest())) {
            throw new PrepareException(422, ErrorCodes.SCHEMA_DRIFT, "MCP 冻结目录内容摘要不一致");
        }
        if (descriptor.tools().isEmpty() || binding.tools().isEmpty()) {
            throw new PrepareException(422, ErrorCodes.MCP_INIT_FAILED, "MCP 没有可绑定工具");
        }
        Map<String, Boolean> readOnlyTools = descriptor.tools().stream()
                .collect(java.util.stream.Collectors.toMap(McpToolDescriptor::name, McpToolDescriptor::readOnly));
        for (AgentManifest.McpToolBinding tool : binding.tools()) {
            if (!Boolean.TRUE.equals(readOnlyTools.get(tool.name()))) {
                throw new PrepareException(
                        403, ErrorCodes.TOOL_DENIED, "一期仅允许准备只读工具：" + binding.key() + "." + tool.name());
            }
        }
    }

    private void validateManifest(AgentManifest manifest) {
        if (blank(manifest.agentKey()) || blank(manifest.version())) {
            throw new PrepareException(422, "INVALID_MANIFEST", "Agent key/version 不能为空");
        }
        if (manifest.model() == null) {
            throw new PrepareException(422, "INVALID_MANIFEST", "Agent 缺少模型配置");
        }
        AgentManifest.ModelSpec model = manifest.model();
        if (!"OPENAI_COMPAT".equalsIgnoreCase(model.providerType())
                || blank(model.baseUrl())
                || blank(model.modelName())) {
            throw new PrepareException(422, "INVALID_MANIFEST", "模型配置不完整或 Provider 不受支持");
        }
        URI modelUri;
        try {
            modelUri = URI.create(model.baseUrl());
        } catch (IllegalArgumentException exception) {
            throw new PrepareException(422, "INVALID_MANIFEST", "模型地址格式非法");
        }
        if (modelUri.getHost() == null
                || !("http".equalsIgnoreCase(modelUri.getScheme()) || "https".equalsIgnoreCase(modelUri.getScheme()))
                || modelUri.getUserInfo() != null) {
            throw new PrepareException(422, "INVALID_MANIFEST", "模型地址只允许受控 HTTP(S) 地址");
        }
        if (manifest.budget() == null) {
            throw new PrepareException(422, "INVALID_MANIFEST", "Agent 缺少执行预算");
        }
        AgentManifest.Budget budget = manifest.budget();
        if (budget.maxIterations() <= 0
                || budget.maxIterations() > properties.run().maxIterationsHardLimit()) {
            throw new PrepareException(422, "BUDGET_EXCEEDED", "maxIterations 超过平台硬上限");
        }
        if (budget.maxToolCalls() <= 0
                || budget.maxToolCalls() > properties.run().maxToolCallsHardLimit()) {
            throw new PrepareException(422, "BUDGET_EXCEEDED", "maxToolCalls 超过平台硬上限");
        }
        if (budget.runTimeoutMs() <= 0
                || budget.runTimeoutMs() > properties.run().maxRunTimeoutMs()) {
            throw new PrepareException(422, "BUDGET_EXCEEDED", "runTimeoutMs 超过平台硬上限");
        }
        if (manifest.executionPolicy() != null && manifest.executionPolicy().scriptExecution()) {
            throw new PrepareException(422, "UNSUPPORTED_EXECUTION_POLICY", "Runtime 不执行脚本或 Shell");
        }
    }

    private static String alias(AgentManifest.McpToolBinding binding, String mcpKey) {
        String alias = binding.alias();
        if (blank(alias)) {
            alias = ToolAliasCodec.alias(mcpKey, binding.name());
        }
        if (!alias.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new PrepareException(422, "TOOL_ALIAS_INVALID", "工具别名格式非法：" + alias);
        }
        return alias;
    }

    private static PrepareException normalizeDependencyFailure(RuntimeException exception, String message) {
        if (exception instanceof PrepareException prepareException) {
            return prepareException;
        }
        if (exception instanceof ControlException controlException) {
            return new PrepareException(
                    controlException.status() == 404 ? 422 : controlException.status(),
                    controlException.code(),
                    message);
        }
        return new PrepareException(503, ErrorCodes.MCP_INIT_FAILED, message);
    }

    private static String safeReason(RuntimeException exception) {
        if (exception instanceof PrepareException prepareException) {
            return prepareException.code();
        }
        if (exception instanceof ControlException controlException) {
            return controlException.code();
        }
        return "依赖不可用";
    }

    private static void requireRequest(ReleasePrepareRequest request) {
        if (request == null
                || request.releaseId() == null
                || blank(request.manifestDigest())
                || request.generation() < 0) {
            throw new PrepareException(400, "BAD_REQUEST", "releaseId、manifestDigest 与 generation 不能为空");
        }
        if (!request.manifestDigest().matches("[0-9a-fA-F]{64}")) {
            throw new PrepareException(400, "BAD_REQUEST", "manifestDigest 必须是 SHA-256");
        }
    }

    private static String warningMessage(List<String> warnings) {
        return warnings.isEmpty() ? null : String.join("; ", warnings);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
