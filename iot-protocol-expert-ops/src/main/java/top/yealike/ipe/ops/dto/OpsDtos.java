package top.yealike.ipe.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 协议通道与运维平台之间的内部契约。字段名与协议侧 DTO 保持一致，便于两边直接映射。 */
public final class OpsDtos {

    private OpsDtos() {}

    public record LoginRequest(
            String protocol,
            String protocolDeviceId,
            String pileCode,
            int pileType,
            int gunCount,
            int protocolVersion,
            String programVersion,
            int networkType,
            String simCard,
            int operator,
            String vendor,
            String model,
            String serialNumber,
            String firmwareVersion,
            int connectorCount,
            String sessionId) {}

    public record DeviceView(
            Long id,
            String sn,
            String protocol,
            String protocolDeviceId,
            Long stationId,
            String stationName,
            int gunCount,
            boolean enabled,
            boolean allowSimulate,
            boolean allowWork,
            int maxPowerPercent,
            boolean online,
            LocalDateTime lastSeen,
            int protocolVersion,
            String programVersion,
            int pileType,
            int networkType,
            String simCard,
            int operator,
            String chargePointVendor,
            String chargePointModel) {}

    public record DevicePatch(boolean allowWork, int maxPowerPercent) {}

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

    public record StartRequest(
            String protocol,
            String protocolDeviceId,
            String gunNo,
            int startType,
            String physicalCard,
            String logicalCard,
            String account) {}

    public record StartResult(boolean accepted, String orderNo, Integer failReason, String message) {}

    public record OrderView(
            Long id,
            String orderNo,
            Long deviceId,
            String protocolDeviceId,
            String gunNo,
            Long stationId,
            String protocol,
            String status,
            Integer ocppTransactionId,
            BigDecimal meterStartWh,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal energy,
            BigDecimal amount,
            String amountSource,
            int stopReason,
            String failReason,
            LocalDateTime createdAt) {}

    public record MeterRequest(BigDecimal energy, BigDecimal amount) {}

    public record StopRequest(int stopReason) {}

    public record FailRequest(String reason) {}

    public record SettleRequest(
            BigDecimal energy, BigDecimal amount, LocalDateTime startTime, LocalDateTime endTime, int stopReason) {}

    public record BindOcppRequest(BigDecimal meterStartWh) {}

    public record BindOcppResult(int transactionId) {}

    public record FrameRequest(
            String protocol,
            String protocolDeviceId,
            String direction,
            int frameType,
            String frameLabel,
            String messageId,
            int sequenceNo,
            boolean encrypted,
            String bodyHex,
            String wireHex,
            String summary,
            String errorMessage,
            String traceId,
            LocalDateTime createdAt) {}

    public record BillRequest(
            String protocol,
            String protocolDeviceId,
            String transactionNo,
            String gunNo,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime tradeTime,
            BigDecimal totalEnergy,
            BigDecimal totalAmount,
            BigDecimal lossTotalEnergy,
            int stopReason,
            int tradeFlag,
            String physicalCard,
            String vin,
            String rawHex) {}

    public record BillResult(boolean accepted, String rejectReason, Long billId, String orderNo) {}

    public record RateModelView(
            String modelNo,
            BigDecimal sharpEnergy,
            BigDecimal sharpService,
            BigDecimal peakEnergy,
            BigDecimal peakService,
            BigDecimal flatEnergy,
            BigDecimal flatService,
            BigDecimal valleyEnergy,
            BigDecimal valleyService,
            int lossRatio,
            List<Integer> segments,
            boolean active,
            LocalDateTime updatedAt) {}

    public record StatusRequest(String protocolDeviceId, int connectorId, String status, String errorCode) {}

    public record AuthorizeResult(String status) {}

    public record StartTransactionRequest(
            String protocolDeviceId, int connectorId, String idTag, BigDecimal meterStart, LocalDateTime startedAt) {}

    public record StartTransactionResult(
            boolean accepted, String orderNo, Integer transactionId, String errorCode, String message) {}

    public record MeterValuesRequest(
            String protocolDeviceId,
            int connectorId,
            Integer transactionId,
            BigDecimal energyWh,
            BigDecimal voltage,
            BigDecimal current,
            Integer soc) {}

    public record StopTransactionRequest(
            String protocolDeviceId, int transactionId, BigDecimal meterStop, LocalDateTime endedAt, String reason) {}

    public record StopTransactionResult(
            boolean accepted, String orderNo, BigDecimal energy, BigDecimal amount, String message) {}

    public record CommandRequest(
            String protocol, String protocolDeviceId, String type, Map<String, Object> params, String operator) {}

    public record CommandResult(boolean success, String message, Map<String, Object> data) {}

    public record CommandSubmit(String type, Map<String, Object> params) {}

    public record BillView(
            Long id,
            Long orderId,
            String orderNo,
            Long deviceId,
            String protocolDeviceId,
            Long stationId,
            String protocol,
            String sourceType,
            String sourceTransactionNo,
            BigDecimal energy,
            BigDecimal amount,
            String currency,
            String amountType,
            int stopReason,
            LocalDateTime endedAt,
            LocalDateTime receivedAt,
            boolean accepted,
            String rejectReason,
            LocalDateTime createdAt) {}
}
