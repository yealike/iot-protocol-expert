package top.yealike.pep.gateway.integration.ops.dto;

import java.math.BigDecimal;

/** 枪口实时数据上报。 */
public record RealtimeRequest(
        String protocol,
        String protocolDeviceId,
        String gunNo,
        int state,
        int gunHomed,
        int plugged,
        BigDecimal outputVoltage,
        BigDecimal outputCurrent,
        int gunLineTemp,
        int soc,
        int batteryMaxTemp,
        int totalChargeMinutes,
        int remainMinutes,
        BigDecimal chargedEnergy,
        BigDecimal chargedAmount,
        int hardwareFault,
        String transactionNo) {}
