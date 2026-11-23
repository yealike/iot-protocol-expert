package top.yealike.pep.ops.diagnostic.domain;

import java.time.LocalDateTime;

public record DiagnosticRequest(
        Long deviceId,
        String sn,
        String protocol,
        String protocolDeviceId,
        String orderNo,
        String raw,
        String question,
        LocalDateTime from,
        LocalDateTime to,
        String operator) {}
