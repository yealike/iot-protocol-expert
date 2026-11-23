package top.yealike.pep.gateway.integration.ops.dto;

import java.util.List;

/** 设备身份映射快照分页。 */
public record DeviceMappingPage(
        List<DeviceMappingRow> content, boolean hasMore, Long nextAfterId, String snapshotToken) {}
