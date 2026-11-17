package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.evaluations.*;
import top.yealike.pep.control.evaluations.EvaluationRecords.*;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;

@RestController
@RequestMapping("/api/ai/v1/evaluation")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "Agent 能力评估", description = "数据集、实验、链路查询和人工评分；数据按当前用户隔离")
public class EvaluationController {
    private final EvaluationService service;
    private final RestClient tempo;
    private final String tempoUrl;

    public EvaluationController(
            EvaluationService service,
            RestClient.Builder builder,
            @Value("${ipe.tempo.query-base-url:}") String tempoUrl) {
        this.service = service;
        this.tempoUrl = tempoUrl;
        var factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        this.tempo = builder.clone().requestFactory(factory).build();
    }

    @GetMapping("/datasets")
    @Operation(summary = "分页查询评测集")
    public List<Dataset> datasets(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return service.datasets(CurrentUser.require(), page, size);
    }

    @PostMapping("/datasets")
    @Operation(summary = "创建评测集")
    public Dataset create(@RequestBody CreateDataset request) {
        return service.createDataset(CurrentUser.require(), request);
    }

    public record UpdateDataset(long revision, String name, String description) {}

    @PutMapping("/datasets/{id}")
    @Operation(summary = "修改评测集")
    public Dataset update(@PathVariable UUID id, @RequestBody UpdateDataset request) {
        return service.updateDataset(
                CurrentUser.require(), id, request.revision(), request.name(), request.description());
    }

    @PostMapping("/datasets/{id}/archive")
    @Operation(summary = "归档评测集")
    public void archive(@PathVariable UUID id) {
        service.archiveDataset(CurrentUser.require(), id);
    }

    @GetMapping("/datasets/{id}/cases")
    @Operation(summary = "分页查询评测用例")
    public List<EvaluationRecords.Case> cases(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        return service.cases(CurrentUser.require(), id, page, size);
    }

    @PostMapping("/datasets/{id}/cases")
    @Operation(summary = "添加评测用例", description = "input.question 为提问，expectedOutput.answer 为参考答案")
    public EvaluationRecords.Case addCase(@PathVariable UUID id, @RequestBody CreateCase request) {
        return service.createCase(CurrentUser.require(), id, request);
    }

    @PostMapping("/experiments")
    @Operation(summary = "启动离线实验", description = "固定 Agent 发布版本、用例输入和评估器版本，异步执行")
    public EvalRun start(@RequestBody CreateExperiment request) {
        return service.startExperiment(CurrentUser.require(), request);
    }

    @GetMapping("/experiments")
    @Operation(summary = "分页查询线上评估和离线实验")
    public List<EvalRun> runs(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return service.evalRuns(CurrentUser.require(), page, size);
    }

    @PostMapping("/experiments/{id}/cancel")
    @Operation(summary = "取消离线实验")
    public void cancel(@PathVariable UUID id) {
        service.cancel(CurrentUser.require(), id);
    }

    public record UpdateCase(long revision, CreateCase value) {}

    @PutMapping("/datasets/{datasetId}/cases/{id}")
    @Operation(summary = "修改用例", description = "支持禁用用例，实验使用创建时快照")
    public EvaluationRecords.Case updateCase(
            @PathVariable UUID datasetId, @PathVariable UUID id, @RequestBody UpdateCase request) {
        return service.updateCase(CurrentUser.require(), datasetId, id, request.revision(), request.value());
    }

    @GetMapping("/experiments/{id}")
    @Operation(summary = "查询实验详情")
    public EvalRun detail(@PathVariable UUID id) {
        return service.requireEvalRun(CurrentUser.require(), id);
    }

    @GetMapping("/experiments/{id}/results")
    @Operation(summary = "分页查询用例执行结果")
    public List<Result> results(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        return service.results(CurrentUser.require(), id, page, size);
    }

    @GetMapping("/results/{id}/scores")
    @Operation(summary = "查询有效评分")
    public List<Score> scores(@PathVariable UUID id) {
        return service.scores(CurrentUser.require(), id);
    }

    @PostMapping("/results/{id}/scores")
    @Operation(summary = "添加人工评分", description = "spanId 可选；指定时评分关联具体 Span，同指标再次评分保留历史并替换当前分数")
    public Score score(@PathVariable UUID id, @RequestBody CreateHumanScore request) {
        if (request == null) throw AiExceptions.badRequest("评分请求不能为空");
        if (request.spanId() != null && !containsSpan(trace(id), request.spanId())) {
            throw AiExceptions.badRequest("Span 不属于该结果的 Trace");
        }
        return service.addHumanScore(CurrentUser.require(), id, request);
    }

    private static boolean containsSpan(Object value, String spanId) {
        if (value instanceof List<?> list) return list.stream().anyMatch(item -> containsSpan(item, spanId));
        if (value instanceof Map<?, ?> map) {
            Object raw = map.get("spanId");
            if (spanId.equals(raw)) return true;
            if (raw instanceof String encoded) {
                try {
                    if (spanId.equals(java.util.HexFormat.of()
                            .formatHex(java.util.Base64.getDecoder().decode(encoded)))) return true;
                } catch (IllegalArgumentException ignored) {
                    /* 其他属性继续递归检查。 */
                }
            }
            return map.values().stream().anyMatch(item -> containsSpan(item, spanId));
        }
        return false;
    }

    @GetMapping("/results/{id}/trace")
    @Operation(summary = "查询完整执行链路", description = "服务端代理 Tempo，返回原始 OTel 属性、事件和 Span")
    public Map<String, Object> trace(@PathVariable UUID id) {
        Result result = service.requireResult(CurrentUser.require(), id);
        if (tempoUrl.isBlank()) throw AiExceptions.unavailable("尚未配置 Tempo 查询地址");
        if (result.traceId() == null || !result.traceId().matches("[0-9a-f]{32}")) {
            throw AiExceptions.notFound("该结果尚无 Trace");
        }
        try {
            String body = tempo.get()
                    .uri(tempoUrl.replaceAll("/+$", "") + "/api/traces/" + result.traceId())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
            return JsonSupport.readMap(body);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
            throw AiExceptions.notFound("Trace 尚未入库或已超过保留时间");
        }
    }
}
