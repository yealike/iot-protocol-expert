package top.yealike.pep.control.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.runs.delegation.A2aTaskService;
import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.DelegateTaskResponse;
import top.yealike.pep.runtime.contract.InternalPaths;

/** 由内部服务签名过滤器保护的同步委派接口。 */
@RestController
@RequestMapping(InternalPaths.CONTROL_A2A_TASKS)
@Tag(name = "内部 Agent 委派")
public class A2aTaskController {

    private final A2aTaskService tasks;

    public A2aTaskController(A2aTaskService tasks) {
        this.tasks = tasks;
    }

    /** 创建一条同步委派。 */
    @PostMapping
    @Operation(summary = "创建委派任务和子 Run")
    public DelegateTaskResponse submit(@RequestBody DelegateTaskRequest request) {
        return tasks.submit(request);
    }

    /** 从父 Run 读取任务的当前状态和结果。 */
    @GetMapping("/{id}")
    @Operation(summary = "查询父 Run 的委派任务")
    public DelegateTaskResponse get(
            @Parameter(description = "任务 ID") @PathVariable UUID id,
            @Parameter(description = "父 Run ID") @RequestParam UUID parentRunId,
            @Parameter(description = "执行令牌") @RequestParam long executionToken,
            @Parameter(description = "父 Runtime 实例") @RequestParam String instanceId) {
        return tasks.get(id, parentRunId, executionToken, instanceId);
    }

    /** 请求目标子 Run 停止并返回 Task 当前状态。 */
    @PostMapping("/{id}:cancel")
    @Operation(summary = "取消委派任务")
    public DelegateTaskResponse cancel(
            @Parameter(description = "任务 ID") @PathVariable UUID id,
            @Parameter(description = "父 Run ID") @RequestParam UUID parentRunId,
            @Parameter(description = "执行令牌") @RequestParam long executionToken,
            @Parameter(description = "父 Runtime 实例") @RequestParam String instanceId) {
        return tasks.cancel(id, parentRunId, executionToken, instanceId);
    }
}
