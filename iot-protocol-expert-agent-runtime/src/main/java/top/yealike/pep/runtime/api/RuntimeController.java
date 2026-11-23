package top.yealike.pep.runtime.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.runtime.api.dto.CancelRequest;
import top.yealike.pep.runtime.contract.InternalPaths;
import top.yealike.pep.runtime.contract.ReleasePrepareRequest;
import top.yealike.pep.runtime.contract.ReleasePrepareResponse;
import top.yealike.pep.runtime.contract.RunDispatchReceipt;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.contract.RuntimeRunStatus;
import top.yealike.pep.runtime.execution.AgentExecutionService;
import top.yealike.pep.runtime.prepare.PrepareService;

/** Runtime 内部协议入口；所有请求由 InternalSecurityFilter 验签。 */
@RestController
@RequestMapping(path = InternalPaths.RUNTIME_BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Agent Runtime 内部接口", description = "仅供 Control 通过签名请求调用")
public class RuntimeController {

    private final PrepareService prepareService;
    private final AgentExecutionService executionService;

    public RuntimeController(PrepareService prepareService, AgentExecutionService executionService) {
        this.prepareService = prepareService;
        this.executionService = executionService;
    }

    @PostMapping("/releases:prepare")
    @Operation(operationId = "prepareRelease", summary = "准备 Agent 发布版本")
    public ReleasePrepareResponse prepare(@RequestBody ReleasePrepareRequest request) {
        return prepareService.prepare(request);
    }

    @PostMapping("/agents/{agentKey}/versions/{version}/runs")
    @Operation(operationId = "dispatchRun", summary = "派发 Agent Run")
    @ApiResponse(responseCode = "202", description = "Run 已受理并开始异步执行")
    public ResponseEntity<RunDispatchReceipt> dispatch(
            @PathVariable String agentKey, @PathVariable String version, @RequestBody RunDispatchRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(executionService.dispatch(agentKey, version, request));
    }

    @GetMapping("/runs/{runId}")
    @Operation(operationId = "getRunStatus", summary = "查询 Runtime Run 状态")
    public RuntimeRunStatus status(@PathVariable UUID runId, @RequestParam long executionToken) {
        return executionService.status(runId, executionToken);
    }

    @PostMapping("/runs/{runId}:cancel")
    @Operation(operationId = "cancelRun", summary = "请求 Runtime 取消 Run")
    public RuntimeRunStatus cancel(
            @PathVariable UUID runId,
            @RequestParam(required = false) Long executionToken,
            @RequestBody(required = false) CancelRequest body) {
        Long token = executionToken != null ? executionToken : body == null ? null : body.executionToken();
        if (token == null || token <= 0) {
            throw new RunRejectedException(400, "BAD_REQUEST", "取消 Run 必须携带 executionToken", null);
        }
        return executionService.cancel(runId, token);
    }
}
