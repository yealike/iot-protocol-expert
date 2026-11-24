package top.yealike.pep.runtime.execution;

import java.util.function.Consumer;
import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.DelegateTaskResponse;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.control.ControlClient;
import top.yealike.pep.runtime.control.ControlException;

/** Control 持久化任务后，由父 Runtime 直接执行本地子 Run。 */
final class LocalNestedDelegateTransport implements DelegateTransport {

    private final ControlClient control;
    private final Consumer<RunDispatchRequest> executeChild;

    LocalNestedDelegateTransport(ControlClient control, Consumer<RunDispatchRequest> executeChild) {
        this.control = control;
        this.executeChild = executeChild;
    }

    @Override
    public DelegateTaskResponse invoke(DelegateTaskRequest request) {
        DelegateTaskResponse result = control.submitDelegation(request);
        if (result == null) {
            throw new ControlException(502, "CONTROL_RESPONSE_INVALID", "控制面未返回委派任务");
        }
        if (!result.created()) {
            return result;
        }
        executeChild.accept(result.child());
        return control.delegation(result.taskId(), request);
    }
}
