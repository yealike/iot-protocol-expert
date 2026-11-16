package top.yealike.ipe.agent.runtime.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.agent.runtime.execution.AgentExecutionService;
import top.yealike.ipe.agent.runtime.prepare.PrepareService;
import top.yealike.ipe.ai.contract.InternalPaths;
import top.yealike.ipe.ai.contract.ReleasePrepareRequest;
import top.yealike.ipe.ai.contract.ReleasePrepareResponse;
import top.yealike.ipe.ai.contract.RunDispatchReceipt;
import top.yealike.ipe.ai.contract.RunDispatchRequest;
import top.yealike.ipe.ai.contract.RuntimeRunStatus;

/** Runtime 内部协议入口；所有请求由 InternalSecurityFilter 验签。 */
@RestController
@RequestMapping(InternalPaths.RUNTIME_BASE)
public class RuntimeController {

    private final PrepareService prepareService;
    private final AgentExecutionService executionService;

    public RuntimeController(PrepareService prepareService, AgentExecutionService executionService) {
        this.prepareService = prepareService;
        this.executionService = executionService;
    }

    @PostMapping("/releases:prepare")
    public ReleasePrepareResponse prepare(@RequestBody ReleasePrepareRequest request) {
        return prepareService.prepare(request);
    }

    @PostMapping("/agents/{agentKey}/versions/{version}/runs")
    public ResponseEntity<RunDispatchReceipt> dispatch(
            @PathVariable String agentKey, @PathVariable String version, @RequestBody RunDispatchRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(executionService.dispatch(agentKey, version, request));
    }

    @GetMapping("/runs/{runId}")
    public RuntimeRunStatus status(@PathVariable UUID runId, @RequestParam long executionToken) {
        return executionService.status(runId, executionToken);
    }

    @PostMapping("/runs/{runId}:cancel")
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

    public record CancelRequest(long executionToken) {}
}
