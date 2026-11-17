package top.yealike.pep.control.agents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.ManifestDigests;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.ToolAliasCodec;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentComponent;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentMcpBinding;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentSkillBinding;
import top.yealike.pep.control.agents.domain.PlatformRecords.CompileIssue;
import top.yealike.pep.control.agents.domain.PlatformRecords.CompileResult;
import top.yealike.pep.control.agents.domain.PlatformRecords.McpRelease;
import top.yealike.pep.control.agents.domain.PlatformRecords.ModelProfile;
import top.yealike.pep.control.agents.domain.PlatformRecords.SkillRelease;
import top.yealike.pep.control.agents.store.AgentStore;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.McpStore;
import top.yealike.pep.control.agents.store.ModelStore;
import top.yealike.pep.control.agents.store.SkillStore;

/** Agent 草稿编译：把草稿与精确版本绑定冻结为可执行 manifest，无远程副作用。 */
@Component
public class AgentCompiler {

    private static final int MAX_MCP = 8;
    private static final int MAX_SKILLS = 16;
    private static final int MAX_VISIBLE_TOOLS = 32;
    private static final int MAX_SYSTEM_PROMPT = 20_000;
    private static final int MAX_ITERATIONS = 12;
    private static final int MAX_TOOL_CALLS = 32;
    private static final long MAX_TIMEOUT_MS = 300_000L;

    private final AgentStore agents;
    private final ModelStore models;
    private final McpStore mcps;
    private final SkillStore skills;
    private final CredentialStore credentials;

    public AgentCompiler(
            AgentStore agents, ModelStore models, McpStore mcps, SkillStore skills, CredentialStore credentials) {
        this.agents = agents;
        this.models = models;
        this.mcps = mcps;
        this.skills = skills;
        this.credentials = credentials;
    }

    public CompileResult compile(AgentComponent draft, String version) {
        List<CompileIssue> errors = new ArrayList<>();
        List<CompileIssue> warnings = new ArrayList<>();
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            errors.add(new CompileIssue("INVALID_VERSION", "版本必须为 x.y.z", "version"));
        }
        if (draft == null) {
            errors.add(new CompileIssue("AGENT_NOT_FOUND", "Agent 草稿不存在", "agent"));
            return new CompileResult(false, null, null, List.copyOf(errors), List.copyOf(warnings));
        }
        if (!draft.enabled()) {
            errors.add(new CompileIssue("AGENT_DISABLED", "Agent 已停用，不能发布", "enabled"));
        }
        String prompt = draft.systemPrompt() == null ? "" : draft.systemPrompt().strip();
        if (prompt.isBlank()) {
            errors.add(new CompileIssue("SYSTEM_PROMPT_EMPTY", "系统指令不能为空", "systemPrompt"));
        } else if (prompt.length() > MAX_SYSTEM_PROMPT) {
            errors.add(new CompileIssue(
                    "SYSTEM_PROMPT_TOO_LARGE", "系统指令不能超过 " + MAX_SYSTEM_PROMPT + " 字符", "systemPrompt"));
        }

        AgentManifest.ModelSpec model = compileModel(draft, errors);
        AgentManifest.Budget budget = compileBudget(draft, errors);
        List<AgentManifest.SkillBinding> skillBindings = compileSkills(draft.id(), errors, warnings);
        List<AgentManifest.McpBinding> mcpBindings = compileMcps(draft.id(), errors, warnings);

        Set<String> aliases = new HashSet<>();
        int visibleTools = 0;
        for (AgentManifest.McpBinding binding : mcpBindings) {
            for (AgentManifest.McpToolBinding tool : binding.tools()) {
                visibleTools++;
                if (!aliases.add(tool.alias().toLowerCase(Locale.ROOT))) {
                    errors.add(new CompileIssue("TOOL_ALIAS_CONFLICT", "工具别名冲突：" + tool.alias(), "mcp.tools"));
                }
                if ("load_skill".equals(tool.alias()) || "read_skill_resource".equals(tool.alias())) {
                    errors.add(
                            new CompileIssue("TOOL_ALIAS_RESERVED", "工具别名与内置 Skill 工具冲突：" + tool.alias(), "mcp.tools"));
                }
            }
        }
        if (!skillBindings.isEmpty()) {
            visibleTools += 2;
        }
        if (visibleTools > MAX_VISIBLE_TOOLS) {
            errors.add(new CompileIssue(
                    "TOOL_BUDGET_EXCEEDED", "模型可见工具数 " + visibleTools + " 超过上限 " + MAX_VISIBLE_TOOLS, "tools"));
        }
        if (visibleTools >= MAX_VISIBLE_TOOLS - 2) {
            warnings.add(new CompileIssue("TOOL_BUDGET_NEAR_LIMIT", "模型可见工具数接近上限，过多工具会降低选择准确率", "tools"));
        }

        if (!errors.isEmpty()) {
            return new CompileResult(false, null, null, List.copyOf(errors), List.copyOf(warnings));
        }
        AgentManifest manifest = new AgentManifest(
                "ipe.agent/v1",
                draft.key(),
                version,
                draft.displayName(),
                prompt,
                model,
                skillBindings,
                mcpBindings,
                budget,
                new AgentManifest.ExecutionPolicy(false, "DENY"));
        return new CompileResult(true, manifest, ManifestDigests.digest(manifest), List.of(), List.copyOf(warnings));
    }

    private AgentManifest.ModelSpec compileModel(AgentComponent draft, List<CompileIssue> errors) {
        if (draft.modelProfileId() == null) {
            errors.add(new CompileIssue("MODEL_REQUIRED", "必须选择模型", "modelProfileId"));
            return null;
        }
        ModelProfile model = models.find(draft.modelProfileId()).orElse(null);
        if (model == null) {
            errors.add(new CompileIssue("MODEL_NOT_FOUND", "模型配置不存在", "modelProfileId"));
            return null;
        }
        if (!model.enabled()) {
            errors.add(new CompileIssue("MODEL_DISABLED", "模型已停用", "modelProfileId"));
        }
        if (!"OPENAI_COMPAT".equals(model.providerType())) {
            errors.add(new CompileIssue("MODEL_PROVIDER_UNSUPPORTED", "一期只支持 OpenAI 兼容模型", "modelProfileId"));
        }
        if (model.credentialId() == null) {
            errors.add(new CompileIssue("MODEL_CREDENTIAL_REQUIRED", "模型必须绑定凭据", "credentialId"));
        }
        var credential = model.credentialId() == null
                ? null
                : credentials.find(model.credentialId()).orElse(null);
        if (model.credentialId() != null && credential == null) {
            errors.add(new CompileIssue("CREDENTIAL_NOT_FOUND", "模型凭据不存在", "credentialId"));
        }
        if (credential != null && !credential.enabled()) {
            errors.add(new CompileIssue("CREDENTIAL_DISABLED", "模型凭据已停用", "credentialId"));
        }
        Map<String, Object> options = new LinkedHashMap<>(model.options());
        if (draft.modelOptions() != null) {
            options.putAll(draft.modelOptions());
        }
        Object temperature = options.get("temperature");
        if (temperature instanceof Number number && (number.doubleValue() < 0 || number.doubleValue() > 2)) {
            errors.add(new CompileIssue("TEMPERATURE_OUT_OF_RANGE", "temperature 必须在 0 到 2 之间", "model.options"));
        }
        Object maxTokens = options.get("maxTokens");
        if (maxTokens instanceof Number number && (number.intValue() < 1 || number.intValue() > 32768)) {
            errors.add(new CompileIssue("MAX_TOKENS_OUT_OF_RANGE", "maxTokens 必须在 1 到 32768 之间", "model.options"));
        }
        if (credentials == null || credential == null) {
            return null;
        }
        return new AgentManifest.ModelSpec(
                model.key(),
                model.providerType(),
                model.baseUrl(),
                model.modelName(),
                credential.key(),
                credential.currentSecretRevision(),
                options);
    }

    private AgentManifest.Budget compileBudget(AgentComponent draft, List<CompileIssue> errors) {
        Map<String, Object> value = draft.budget() == null ? Map.of() : draft.budget();
        int iterations = intValue(value.get("maxIterations"), 6);
        int toolCalls = intValue(value.get("maxToolCalls"), 12);
        long timeout = longValue(value.get("runTimeoutMs"), 120_000L);
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            errors.add(new CompileIssue(
                    "BUDGET_ITERATIONS_EXCEEDED", "maxIterations 必须在 1 到 " + MAX_ITERATIONS + " 之间", "budget"));
        }
        if (toolCalls < 1 || toolCalls > MAX_TOOL_CALLS) {
            errors.add(new CompileIssue(
                    "BUDGET_TOOL_CALLS_EXCEEDED", "maxToolCalls 必须在 1 到 " + MAX_TOOL_CALLS + " 之间", "budget"));
        }
        if (timeout < 5_000L || timeout > MAX_TIMEOUT_MS) {
            errors.add(new CompileIssue(
                    "BUDGET_TIMEOUT_EXCEEDED", "runTimeoutMs 必须在 5000 到 " + MAX_TIMEOUT_MS + " 之间", "budget"));
        }
        return new AgentManifest.Budget(iterations, toolCalls, timeout);
    }

    private List<AgentManifest.SkillBinding> compileSkills(
            UUID agentId, List<CompileIssue> errors, List<CompileIssue> warnings) {
        List<AgentSkillBinding> bindings = agents.skillBindings(agentId);
        if (bindings.size() > MAX_SKILLS) {
            errors.add(new CompileIssue("SKILL_LIMIT_EXCEEDED", "一个 Agent 最多绑定 " + MAX_SKILLS + " 个 Skill", "skills"));
        }
        Set<String> keys = new HashSet<>();
        List<AgentManifest.SkillBinding> result = new ArrayList<>();
        int always = 0;
        for (AgentSkillBinding binding : bindings) {
            if (!keys.add(binding.componentKey())) {
                errors.add(
                        new CompileIssue("DUPLICATE_SKILL", "同一 Skill 只能绑定一个版本：" + binding.componentKey(), "skills"));
                continue;
            }
            if (!"AUTO".equals(binding.loadMode()) && !"ALWAYS".equals(binding.loadMode())) {
                errors.add(new CompileIssue("INVALID_LOAD_MODE", "Skill 加载方式只支持 AUTO 或 ALWAYS", "skills"));
            }
            if ("ALWAYS".equals(binding.loadMode())) {
                always++;
            }
            SkillRelease release = skills.findRelease(binding.skillReleaseId()).orElse(null);
            if (release == null) {
                errors.add(
                        new CompileIssue("SKILL_RELEASE_NOT_FOUND", "Skill 版本不存在：" + binding.componentKey(), "skills"));
                continue;
            }
            if (!"ACTIVE".equals(release.state())) {
                errors.add(
                        new CompileIssue("SKILL_NOT_PUBLISHED", "Skill 版本未发布或已停用：" + binding.componentKey(), "skills"));
            }
            result.add(new AgentManifest.SkillBinding(
                    binding.componentKey(),
                    binding.version(),
                    release.bundleDigest(),
                    binding.loadMode(),
                    binding.order()));
        }
        if (always > 2) {
            errors.add(new CompileIssue("ALWAYS_SKILL_LIMIT", "ALWAYS 模式的 Skill 最多 2 个", "skills"));
        } else if (always > 0) {
            warnings.add(new CompileIssue("ALWAYS_SKILL_COST", "ALWAYS 模式会在每轮注入 Skill 正文，注意上下文成本", "skills"));
        }
        result.sort(java.util.Comparator.comparingInt(AgentManifest.SkillBinding::order));
        return List.copyOf(result);
    }

    private List<AgentManifest.McpBinding> compileMcps(
            UUID agentId, List<CompileIssue> errors, List<CompileIssue> warnings) {
        List<AgentMcpBinding> bindings = agents.mcpBindings(agentId);
        Map<UUID, McpRelease> releaseCache = new HashMap<>();
        Map<UUID, List<AgentMcpBinding>> grouped = new LinkedHashMap<>();
        for (AgentMcpBinding binding : bindings) {
            grouped.computeIfAbsent(binding.mcpReleaseId(), ignored -> new ArrayList<>())
                    .add(binding);
        }
        if (grouped.size() > MAX_MCP) {
            errors.add(new CompileIssue("MCP_LIMIT_EXCEEDED", "一个 Agent 最多绑定 " + MAX_MCP + " 个 MCP", "mcp"));
        }
        Set<String> keys = new HashSet<>();
        List<AgentManifest.McpBinding> result = new ArrayList<>();
        for (Map.Entry<UUID, List<AgentMcpBinding>> entry : grouped.entrySet()) {
            McpRelease release = releaseCache.computeIfAbsent(
                    entry.getKey(), id -> mcps.findRelease(id).orElse(null));
            if (release == null) {
                errors.add(new CompileIssue("MCP_RELEASE_NOT_FOUND", "MCP 版本不存在", "mcp"));
                continue;
            }
            if (!keys.add(release.componentKey())) {
                errors.add(new CompileIssue("DUPLICATE_MCP", "同一 MCP 只能绑定一个版本：" + release.componentKey(), "mcp"));
                continue;
            }
            if (!"ACTIVE".equals(release.state())) {
                errors.add(new CompileIssue("MCP_NOT_PUBLISHED", "MCP 版本未发布或已停用：" + release.componentKey(), "mcp"));
            }
            List<AgentManifest.McpToolBinding> tools = new ArrayList<>();
            Set<String> names = new HashSet<>();
            boolean required = false;
            for (AgentMcpBinding binding : entry.getValue()) {
                required = required || binding.required();
                if (!names.add(binding.toolName())) {
                    continue;
                }
                McpToolDescriptor descriptor = release.tools().stream()
                        .filter(tool -> tool.name().equals(binding.toolName()))
                        .findFirst()
                        .orElse(null);
                if (descriptor == null) {
                    errors.add(new CompileIssue(
                            "MCP_TOOL_NOT_FOUND",
                            "MCP 冻结目录中没有工具 " + release.componentKey() + "." + binding.toolName(),
                            "mcp.tools"));
                    continue;
                }
                if (descriptor.schemaDigest() == null
                        || descriptor.schemaDigest().isBlank()) {
                    errors.add(new CompileIssue(
                            "MCP_SCHEMA_DIGEST_MISSING",
                            "MCP 工具缺少 Schema 摘要：" + release.componentKey() + "." + binding.toolName(),
                            "mcp.tools"));
                    continue;
                }
                if (!mcps.toolCategories(release.componentId())
                        .getOrDefault(binding.toolName(), top.yealike.pep.runtime.contract.McpToolExecutionCategory.UNKNOWN)
                        .readOnly()) {
                    errors.add(new CompileIssue(
                            "MCP_TOOL_NOT_READ_ONLY",
                            "一期仅允许绑定已审核的只读工具：" + release.componentKey() + "." + binding.toolName(),
                            "mcp.tools"));
                    continue;
                }
                tools.add(new AgentManifest.McpToolBinding(
                        binding.toolName(),
                        ToolAliasCodec.alias(release.componentKey(), binding.toolName()),
                        descriptor.schemaDigest()));
            }
            if (!required) {
                warnings.add(
                        new CompileIssue("OPTIONAL_MCP", "MCP 为可选依赖，运行时不可用会从工具集合移除：" + release.componentKey(), "mcp"));
            }
            result.add(new AgentManifest.McpBinding(
                    release.componentKey(), release.version(), required, release.catalogDigest(), tools));
        }
        return List.copyOf(result);
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
