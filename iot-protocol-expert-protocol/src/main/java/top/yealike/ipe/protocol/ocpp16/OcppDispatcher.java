package top.yealike.ipe.protocol.ocpp16;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.ocpp16.codec.OcppConstants;
import top.yealike.ipe.protocol.ocpp16.codec.OcppException;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessage;
import top.yealike.ipe.protocol.ocpp16.codec.OcppMessageCodec;
import top.yealike.ipe.protocol.ops.OpsClient;
import top.yealike.ipe.protocol.ops.OpsDtos;

/** OCPP 1.6J 上行消息处理：协议语义在本类，业务状态与账务提交运维平台。 */
@Service
public class OcppDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OcppDispatcher.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final OpsClient ops;
    private final OcppConfigurationStore configurationStore;
    private final ProtocolProperties properties;

    public OcppDispatcher(OpsClient ops, OcppConfigurationStore configurationStore, ProtocolProperties properties) {
        this.ops = ops;
        this.configurationStore = configurationStore;
        this.properties = properties;
    }

    public JsonNode handle(String chargePointId, OcppMessage message, String sessionId) {
        String action = message.action();
        JsonNode payload = message.payload() == null ? OcppMessageCodec.object() : message.payload();
        try {
            return switch (action) {
                case OcppConstants.BOOT_NOTIFICATION -> bootNotification(chargePointId, payload, sessionId);
                case OcppConstants.HEARTBEAT -> heartbeat(chargePointId);
                case OcppConstants.STATUS_NOTIFICATION -> statusNotification(chargePointId, payload);
                case OcppConstants.AUTHORIZE -> authorize(payload);
                case OcppConstants.START_TRANSACTION -> startTransaction(chargePointId, payload);
                case OcppConstants.METER_VALUES -> meterValues(chargePointId, payload);
                case OcppConstants.STOP_TRANSACTION -> stopTransaction(chargePointId, payload);
                case OcppConstants.DATA_TRANSFER -> dataTransfer();
                case OcppConstants.DIAGNOSTICS_STATUS_NOTIFICATION, OcppConstants.FIRMWARE_STATUS_NOTIFICATION ->
                    OcppMessageCodec.object();
                default -> throw new OcppException(OcppConstants.ERROR_NOT_IMPLEMENTED, "平台未实现动作: " + action);
            };
        } catch (OcppException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("处理 OCPP 桩 {} 动作 {} 失败: {}", chargePointId, action, e.getMessage(), e);
            throw new OcppException(OcppConstants.ERROR_INTERNAL, e.getMessage(), e);
        }
    }

    private JsonNode bootNotification(String chargePointId, JsonNode payload, String sessionId) {
        String vendor = text(payload, "chargePointVendor", "未知厂商");
        String model = text(payload, "chargePointModel", "未知型号");
        String serial = text(payload, "chargePointSerialNumber", chargePointId);
        String firmware = text(payload, "firmwareVersion", null);
        ops.bootOcpp(new OpsDtos.LoginRequest(
                "OCPP16J",
                chargePointId,
                chargePointId,
                0,
                Math.max(properties.getOcppDefaultConnectorCount(), 1),
                16,
                firmware,
                0,
                null,
                0,
                vendor,
                model,
                serial,
                firmware,
                Math.max(properties.getOcppDefaultConnectorCount(), 1),
                sessionId));
        configurationStore.remove(chargePointId);
        ObjectNode response = OcppMessageCodec.object();
        response.put("status", OcppConstants.ACCEPTED);
        response.put("currentTime", Instant.now().toString());
        response.put("interval", 300);
        log.info("OCPP 桩 {} BootNotification 通过（{} / {}）", chargePointId, vendor, model);
        return response;
    }

    private JsonNode heartbeat(String chargePointId) {
        ops.ocppHeartbeat(chargePointId);
        ObjectNode response = OcppMessageCodec.object();
        response.put("currentTime", Instant.now().toString());
        return response;
    }

    private JsonNode statusNotification(String chargePointId, JsonNode payload) {
        int connectorId = intValue(payload, "connectorId", 0);
        String status = text(payload, "status", OcppConstants.STATUS_AVAILABLE);
        String errorCode = text(payload, "errorCode", "NoError");
        ops.ocppStatus(new OpsDtos.StatusRequest(chargePointId, connectorId, status, errorCode));
        return OcppMessageCodec.object();
    }

    private JsonNode authorize(JsonNode payload) {
        String idTag = text(payload, "idTag", null);
        if (idTag == null || idTag.isBlank()) {
            throw new OcppException(OcppConstants.ERROR_PROPERTY, "idTag 不能为空");
        }
        String status = ops.authorize(idTag);
        ObjectNode idTagInfo = OcppMessageCodec.object();
        idTagInfo.put("status", status);
        ObjectNode response = OcppMessageCodec.object();
        response.set("idTagInfo", idTagInfo);
        return response;
    }

    private JsonNode startTransaction(String chargePointId, JsonNode payload) {
        int connectorId = intValue(payload, "connectorId", 0);
        String idTag = text(payload, "idTag", null);
        if (idTag == null || idTag.isBlank()) {
            throw new OcppException(OcppConstants.ERROR_PROPERTY, "idTag 不能为空");
        }
        BigDecimal meterStart = decimal(payload, "meterStart", BigDecimal.ZERO);
        LocalDateTime startedAt = timestamp(payload, "timestamp");
        OpsDtos.StartTransactionResult result = ops.startTransaction(
                new OpsDtos.StartTransactionRequest(chargePointId, connectorId, idTag, meterStart, startedAt));
        if (!result.accepted()) {
            throw new OcppException(
                    result.errorCode() == null ? OcppConstants.ERROR_OCCURRENCE : result.errorCode(), result.message());
        }
        ObjectNode idTagInfo = OcppMessageCodec.object();
        idTagInfo.put("status", OcppConstants.ACCEPTED);
        ObjectNode response = OcppMessageCodec.object();
        response.put("transactionId", result.transactionId());
        response.set("idTagInfo", idTagInfo);
        log.info(
                "OCPP 桩 {} 枪 {} 开始交易 {}（整数交易号 {}）",
                chargePointId,
                connectorId,
                result.orderNo(),
                result.transactionId());
        return response;
    }

    private JsonNode meterValues(String chargePointId, JsonNode payload) {
        int connectorId = intValue(payload, "connectorId", 0);
        Integer transactionId =
                payload.has("transactionId") && !payload.get("transactionId").isNull()
                        ? payload.get("transactionId").asInt()
                        : null;
        MeterSample sample = readMeterSample(payload);
        ops.meterValues(new OpsDtos.MeterValuesRequest(
                chargePointId,
                connectorId,
                transactionId,
                sample.energyWh(),
                sample.voltage(),
                sample.current(),
                sample.soc()));
        return OcppMessageCodec.object();
    }

    private JsonNode stopTransaction(String chargePointId, JsonNode payload) {
        int transactionId = intValue(payload, "transactionId", -1);
        BigDecimal meterStop = decimal(payload, "meterStop", BigDecimal.ZERO);
        LocalDateTime endedAt = timestamp(payload, "timestamp");
        String reason = text(payload, "reason", "Local");
        OpsDtos.StopTransactionResult result = ops.stopTransaction(
                new OpsDtos.StopTransactionRequest(chargePointId, transactionId, meterStop, endedAt, reason));
        if (!result.accepted()) {
            throw new OcppException(OcppConstants.ERROR_OCCURRENCE, result.message());
        }
        log.info("OCPP 桩 {} 交易 {} 结束：{} kWh，{} 元", chargePointId, result.orderNo(), result.energy(), result.amount());
        return acceptedIdTagInfo();
    }

    private JsonNode dataTransfer() {
        ObjectNode response = OcppMessageCodec.object();
        response.put("status", OcppConstants.ACCEPTED);
        return response;
    }

    private JsonNode acceptedIdTagInfo() {
        ObjectNode idTagInfo = OcppMessageCodec.object();
        idTagInfo.put("status", OcppConstants.ACCEPTED);
        ObjectNode response = OcppMessageCodec.object();
        response.set("idTagInfo", idTagInfo);
        return response;
    }

    private MeterSample readMeterSample(JsonNode payload) {
        BigDecimal energyWh = null;
        BigDecimal voltage = null;
        BigDecimal current = null;
        Integer soc = null;
        JsonNode meterValues = payload.get("meterValue");
        if (meterValues != null && meterValues.isArray()) {
            for (JsonNode meterValue : meterValues) {
                JsonNode sampledValues = meterValue.get("sampledValue");
                if (sampledValues == null || !sampledValues.isArray()) {
                    continue;
                }
                for (JsonNode sampled : sampledValues) {
                    String measurand = text(sampled, "measurand", "Energy.Active.Import.Register");
                    BigDecimal value = decimal(sampled, "value", null);
                    if (value == null) {
                        continue;
                    }
                    String unit = text(sampled, "unit", "");
                    switch (measurand) {
                        case "Energy.Active.Import.Register" -> energyWh = toWh(value, unit);
                        case "Voltage" -> voltage = value;
                        case "Current.Import" -> current = value;
                        case "SoC" -> soc = value.intValue();
                        default -> {}
                    }
                }
            }
        }
        return new MeterSample(energyWh, voltage, current, soc);
    }

    private static BigDecimal toWh(BigDecimal value, String unit) {
        return switch (unit == null ? "" : unit) {
            case "kWh" -> value.multiply(THOUSAND);
            case "MWh" -> value.multiply(new BigDecimal("1000000"));
            default -> value;
        };
    }

    private static LocalDateTime timestamp(JsonNode payload, String field) {
        String value = text(payload, field, null);
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.systemDefault());
            } catch (DateTimeParseException ignored) {
                return LocalDateTime.now();
            }
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asString();
        return value == null ? fallback : value;
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        return node.get(field).asInt(fallback);
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asString());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private record MeterSample(BigDecimal energyWh, BigDecimal voltage, BigDecimal current, Integer soc) {}
}
