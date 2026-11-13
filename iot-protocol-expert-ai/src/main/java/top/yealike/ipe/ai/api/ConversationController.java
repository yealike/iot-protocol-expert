package top.yealike.ipe.ai.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.ai.conversation.ConversationRecords.Conversation;
import top.yealike.ipe.ai.conversation.ConversationService;
import top.yealike.ipe.ai.identity.CurrentUser;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.run.RunRecords.Run;
import top.yealike.ipe.ai.run.RunService;

/** 会话与 Run 的 HTTP 契约：提交与事件流分离，提交负责幂等，事件流负责续传。 */
@RestController
@RequestMapping("/api/ai")
public class ConversationController {

    private final ConversationService conversations;
    private final RunService runs;

    public ConversationController(ConversationService conversations, RunService runs) {
        this.conversations = conversations;
        this.runs = runs;
    }

    public record CreateConversation(String agentKey, String title) {}

    public record SubmitRun(String question, String idempotencyKey, Integer priority) {}

    @PostMapping("/conversations")
    public Conversation create(@RequestBody(required = false) CreateConversation request) {
        UserIdentity identity = CurrentUser.require();
        String agentKey = request == null ? null : request.agentKey();
        String title = request == null ? null : request.title();
        return conversations.create(identity, agentKey, title);
    }

    @GetMapping("/conversations")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
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
    public Object detail(@PathVariable UUID id) {
        UserIdentity identity = CurrentUser.require();
        return conversations.detail(identity, id);
    }

    @PostMapping("/conversations/{id}/runs")
    public Object submit(@PathVariable UUID id, @RequestBody SubmitRun request) {
        UserIdentity identity = CurrentUser.require();
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Run run = runs.submit(identity, id, request.question(), request.idempotencyKey(), request.priority());
        return runs.toView(run);
    }

    @GetMapping("/runs/{id}")
    public Object run(@PathVariable UUID id) {
        UserIdentity identity = CurrentUser.require();
        return runs.view(identity, id);
    }

    @PostMapping("/runs/{id}/pause")
    public Map<String, Object> pause(@PathVariable UUID id) {
        runs.pause(CurrentUser.require(), id);
        return Map.of("ok", true, "status", "PAUSE_REQUESTED");
    }

    @PostMapping("/runs/{id}/resume")
    public Map<String, Object> resume(@PathVariable UUID id) {
        runs.resume(CurrentUser.require(), id);
        return Map.of("ok", true, "status", "QUEUED");
    }

    @PostMapping("/runs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        String status = runs.cancel(CurrentUser.require(), id);
        return Map.of("ok", true, "status", status, "note", "已发出的外部请求不保证远端未执行；工具调用状态可能为 UNKNOWN");
    }

    @GetMapping("/conversations/{id}/runs")
    public List<Run> history(@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        UserIdentity identity = CurrentUser.require();
        conversations.require(identity, id);
        return runs.history(id, limit);
    }
}
