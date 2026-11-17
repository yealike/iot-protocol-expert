package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.PlatformRecords.AgentCatalogItem;
import top.yealike.pep.control.conversations.ConversationRecords.Conversation;
import top.yealike.pep.control.conversations.ConversationService;
import top.yealike.pep.control.runs.RunRecords.Run;
import top.yealike.pep.control.runs.RunService;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 会话与 Run 的 HTTP 契约：提交与事件流分离，提交负责幂等，事件流负责续传。 */
@RestController
@RequestMapping("/api/ai/v1")
@Tag(name = "AI 对话与 Run", description = "Agent 会话、消息提交、任务状态和历史记录")
public class ConversationController {

    private final ConversationService conversations;
    private final RunService runs;
    private final AgentService agents;

    public ConversationController(ConversationService conversations, RunService runs, AgentService agents) {
        this.conversations = conversations;
        this.runs = runs;
        this.agents = agents;
    }

    public record CreateConversation(
            @Schema(description = "Agent 唯一标识", example = "protocol-expert")
            String agentKey,

            @Schema(description = "指定已发布版本；省略时使用当前可用版本", example = "1.0.0")
            String version,

            @Schema(description = "会话标题；省略时由服务端生成") String title) {}

    public record SubmitRun(
            @Schema(description = "本轮用户输入；优先使用此字段", example = "分析这条协议报文")
            String content,

            @Schema(description = "兼容旧客户端的用户问题字段") String question,

            @Schema(description = "客户端生成的幂等键，用于安全重试", example = "6d44b82c-...")
            String idempotencyKey,

            @Schema(description = "任务优先级；当前仅支持 100", example = "100")
            Integer priority) {}

    @GetMapping("/agent-catalog")
    @Operation(summary = "查询可用 Agent", description = "返回当前用户有权使用且已发布的 Agent 目录")
    public List<AgentCatalogItem> catalog() {
        return agents.catalog(CurrentUser.require());
    }

    @PostMapping("/conversations")
    @Operation(summary = "新建对话", description = "创建并固定到指定 Agent 发布版本的会话")
    public Conversation create(@RequestBody(required = false) CreateConversation request) {
        UserIdentity identity = CurrentUser.require();
        String agentKey = request == null ? null : request.agentKey();
        String version = request == null ? null : request.version();
        String title = request == null ? null : request.title();
        return conversations.create(identity, agentKey, version, title);
    }

    @GetMapping("/conversations")
    @Operation(summary = "分页查询对话", description = "仅返回当前用户拥有的会话")
    public Map<String, Object> list(
            @Parameter(description = "从 0 开始的页码", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量，最大 100", example = "20") @RequestParam(defaultValue = "20") int size) {
        UserIdentity identity = CurrentUser.require();
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        List<Conversation> content = conversations.list(identity, page, normalizedSize);
        long total = conversations.count(identity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", Math.max(page, 0));
        result.put("size", normalizedSize);
        result.put("total", total);
        result.put("totalPages", normalizedSize == 0 ? 0 : (total + normalizedSize - 1) / normalizedSize);
        return result;
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "查询对话详情", description = "返回会话消息和当前运行任务")
    public Object detail(@Parameter(description = "会话 ID") @PathVariable UUID id) {
        UserIdentity identity = CurrentUser.require();
        return conversations.detail(identity, id);
    }

    @PostMapping("/conversations/{id}/runs")
    @Operation(summary = "提交用户消息", description = "异步创建 Run，返回 202；回答和执行进度通过事件流接收")
    public ResponseEntity<Object> submit(
            @Parameter(description = "目标会话 ID") @PathVariable UUID id, @RequestBody SubmitRun request) {
        UserIdentity identity = CurrentUser.require();
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String content =
                request.content() == null || request.content().isBlank() ? request.question() : request.content();
        Run run = runs.submit(identity, id, content, request.idempotencyKey(), request.priority());
        return ResponseEntity.accepted().body(runs.toView(run));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "查询 Run 状态")
    public Object run(@Parameter(description = "Run ID") @PathVariable UUID id) {
        UserIdentity identity = CurrentUser.require();
        return runs.view(identity, id);
    }

    @PostMapping("/runs/{id}/cancel")
    @Operation(summary = "取消 Run", description = "发出协作式取消请求；已发送到外部工具的请求不保证撤回")
    public Map<String, Object> cancel(@Parameter(description = "Run ID") @PathVariable UUID id) {
        String status = runs.cancel(CurrentUser.require(), id);
        return Map.of("ok", true, "status", status, "note", "已发出的外部请求不保证远端未执行；工具调用状态可能为 UNKNOWN");
    }

    @GetMapping("/conversations/{id}/runs")
    @Operation(summary = "查询对话运行历史")
    public List<Run> history(
            @Parameter(description = "会话 ID") @PathVariable UUID id,
            @Parameter(description = "最多返回条数，范围 1 到 100", example = "20") @RequestParam(defaultValue = "20")
                    int limit) {
        UserIdentity identity = CurrentUser.require();
        conversations.require(identity, id);
        return runs.history(id, limit);
    }
}
