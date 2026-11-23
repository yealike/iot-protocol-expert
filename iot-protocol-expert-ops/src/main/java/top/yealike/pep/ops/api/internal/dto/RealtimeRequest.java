package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "RealtimeRequest", description = "枪口实时状态上报")
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
