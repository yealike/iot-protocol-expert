package top.yealike.pep.runtime.execution;

import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.DelegateTaskResponse;

/** 委派工具与子任务传输之间的契约。 */
@FunctionalInterface
public interface DelegateTransport {

    /** 创建或复用任务并返回结果。 */
    DelegateTaskResponse invoke(DelegateTaskRequest request);
}
