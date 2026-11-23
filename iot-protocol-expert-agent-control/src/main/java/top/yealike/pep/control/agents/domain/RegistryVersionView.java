package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 单个组件版本的注册状态详情。 */
public record RegistryVersionView(
        String componentType,
        String componentKey,
        String componentVersion,
        String serviceName,
        Instant checkedAt,
        UUID releaseId,
        String desiredState,
        String observedState,
        String lastError,
        String authority,
        List<RegistryInstanceView> instances,
        boolean healthy,
        String releaseState) {

    public RegistryVersionView {
        instances = instances == null ? List.of() : List.copyOf(instances);
    }
}
