package top.yealike.pep.control.agents.domain;

import java.util.List;

/** 注册中心实例。metadata 以有序键值对返回，避免 HTTP 契约直接暴露 Map。 */
public record RegistryInstanceView(String ip, int port, List<RegistryMetadataItem> metadata) {

    public RegistryInstanceView {
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
    }
}
