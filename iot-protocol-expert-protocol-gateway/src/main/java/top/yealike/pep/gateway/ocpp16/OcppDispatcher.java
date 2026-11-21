package top.yealike.pep.gateway.ocpp16;

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
import top.yealike.pep.codec.ocpp16.OcppConstants;
import top.yealike.pep.codec.ocpp16.OcppException;
import top.yealike.pep.codec.ocpp16.OcppMessage;
import top.yealike.pep.codec.ocpp16.OcppMessageCodec;
import top.yealike.pep.gateway.core.DeviceIdentityRegistry;
import top.yealike.pep.gateway.core.ProtocolType;
import top.yealike.pep.gateway.ops.OpsClient;
import top.yealike.pep.gateway.ops.OpsDtos;

/** OCPP 1.6J 上行消息处理：协议语义在本类，业务状态与账务提交运维平台。 */
@Service
public class OcppDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OcppDispatcher.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final OpsClient ops;
    private final OcppConfigurationStore configurationStore;
    private final DeviceIdentityRegistry identities;

    public OcppDispatcher(OpsClient ops, OcppConfigurationStore configurationStore, DeviceIdentityRegistry identities) {
        this.ops = ops;
        this.configurationStore = configurationStore;
        this.identities = identities;
    }

    public JsonNode handle(String deviceSn, OcppMessage message, String sessionId) {
        String action = message.action();
        JsonNode payload = message.payload() == null ? OcppMessageCodec.object() : message.payload();
        try {
            DeviceIdentityRegistry.Device identity = identities.resolvePlatform(ProtocolType.OCPP16J, deviceSn);
            return switch (action) {
                case OcppConstants.BOOT_NOTIFICATION -> bootNotification(identity, payload, sessionId);
                case OcppConstants.HEARTBEAT -> heartbeat(deviceSn);
                case OcppConstants.STATUS_NOTIFICATION -> statusNotification(identity, payload);
                case OcppConstants.AUTHORIZE -> authorize(payload);
                case OcppConstants.START_TRANSACTION -> startTransaction(identity, payload);
                case OcppConstants.METER_VALUES -> meterValues(identity, payload);
                case OcppConstants.STOP_TRANSACTION -> stopTransaction(deviceSn, payload);
                case OcppConstants.DATA_TRANSFER -> dataTransfer();
                case OcppConstants.DIAGNOSTICS_STATUS_NOTIFICATION, OcppConstants.FIRMWARE_STATUS_NOTIFICATION ->
                    OcppMessageCodec.object();
                default -> throw new OcppException(OcppConstants.ERROR_NOT_IMPLEMENTED, "平台未实现动作: " + action);
            };
        } catch (OcppException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("处理 OCPP 设备 {} 动作 {} 失败: {}", deviceSn, action, e.getMessage(), e);
            throw new OcppException(OcppConstants.ERROR_INTERNAL, e.getMessage(), e);
        }
    }

    private JsonNode bootNotification(DeviceIdentityRegistry.Device identity, JsonNode payload, String sessionId) {
        String vendor = text(payload, "chargePointVendor", "未知厂商");
        String model = text(payload, "chargePointModel", "未知型号");
        String serial = text(payload, "chargePointSerialNumber", identity.thirdPartyDeviceId());
        String firmware = text(payload, "firmwareVersion", null);
        ops.bootOcpp(new OpsDtos.LoginRequest(
                "OCPP16J",
                identity.deviceSn(),
                identity.thirdPartyDeviceId(),
                0,
                identity.connectorCount(),
                16,
                firmware,
                0,
                null,
                0,
                vendor,
                model,
                serial,
                firmware,
                identity.connectorCount(),
                sessionId));
        configurationStore.remove(identity.deviceSn());
        ObjectNode response = OcppMessageCodec.object();
        response.put("status", OcppConstants.ACCEPTED);
        response.put("currentTime", Instant.now().toString());
        response.put("interval", 300);
        log.info(
                "OCPP 设备 {}（chargePointId {}）BootNotification 通过（{} / {}）",
                identity.deviceSn(),
                identity.thirdPartyDeviceId(),
                vendor,
                model);
        return response;
    }

    private JsonNode heartbeat(String deviceSn) {
        ops.ocppHeartbeat(deviceSn);
        ObjectNode response = OcppMessageCodec.object();
        response.put("currentTime", Instant.now().toString());
        return response;
    }

    private JsonNode statusNotification(DeviceIdentityRegistry.Device identity, JsonNode payload) {
        int protocolConnectorId = intValue(payload, "connectorId", 0);
        int connectorId = platformConnectorId(identity, protocolConnectorId);
        String status = text(payload, "status", OcppConstants.STATUS_AVAILABLE);
        String errorCode = text(payload, "errorCode", "NoError");
        ops.ocppStatus(new OpsDtos.StatusRequest(identity.deviceSn(), connectorId, status, errorCode));
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

    private JsonNode startTransaction(DeviceIdentityRegistry.Device identity, JsonNode payload) {
        int protocolConnectorId = intValue(payload, "connectorId", 0);
        int connectorId = platformConnectorId(identity, protocolConnectorId);
        String idTag = text(payload, "idTag", null);
        if (idTag == null || idTag.isBlank()) {
            throw new OcppException(OcppConstants.ERROR_PROPERTY, "idTag 不能为空");
        }
        BigDecimal meterStart = decimal(payload, "meterStart", BigDecimal.ZERO);
        LocalDateTime startedAt = timestamp(payload, "timestamp");
        OpsDtos.StartTransactionResult result = ops.startTransaction(
                new OpsDtos.StartTransactionRequest(identity.deviceSn(), connectorId, idTag, meterStart, startedAt));
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
                "OCPP 设备 {} 枪 {} 开始交易 {}（整数交易号 {}）",
                identity.deviceSn(),
                String.format(java.util.Locale.ROOT, "%02d", connectorId),
                result.orderNo(),
                result.transactionId());
        return response;
    }

    private JsonNode meterValues(DeviceIdentityRegistry.Device identity, JsonNode payload) {
        int protocolConnectorId = intValue(payload, "connectorId", 0);
        int connectorId = platformConnectorId(identity, protocolConnectorId);
        Integer transactionId =
                payload.has("transactionId") && !payload.get("transactionId").isNull()
                        ? payload.get("transactionId").asInt()
                        : null;
        JsonNode values = payload.get("meterValue");
        if (values != null && values.isArray()) {
            for (JsonNode meterValue : values) {
                MeterSample sample = readMeterSample(meterValue);
                ops.meterValues(new OpsDtos.MeterValuesRequest(
                        identity.deviceSn(),
                        connectorId,
                        transactionId,
                        sample.energyWh(),
                        sample.voltage(),
                        sample.current(),
                        sample.soc(),
                        sample.powerKw(),
                        sample.temperature(),
                        text(meterValue, "timestamp", null)));
            }
        }
        return OcppMessageCodec.object();
    }

    private JsonNode stopTransaction(String deviceSn, JsonNode payload) {
        int transactionId = intValue(payload, "transactionId", -1);
        BigDecimal meterStop = decimal(payload, "meterStop", BigDecimal.ZERO);
        LocalDateTime endedAt = timestamp(payload, "timestamp");
        String reason = text(payload, "reason", "Local");
        OpsDtos.StopTransactionResult result = ops.stopTransaction(
                new OpsDtos.StopTransactionRequest(deviceSn, transactionId, meterStop, endedAt, reason));
        if (!result.accepted()) {
            throw new OcppException(OcppConstants.ERROR_OCCURRENCE, result.message());
        }
        log.info("OCPP 设备 {} 交易 {} 结束：{} kWh，{} 元", deviceSn, result.orderNo(), result.energy(), result.amount());
        return acceptedIdTagInfo();
    }

    private static int platformConnectorId(DeviceIdentityRegistry.Device identity, int protocolConnectorId) {
        if (protocolConnectorId <= 0) {
            return protocolConnectorId;
        }
        try {
            DeviceIdentityRegistry.Connector connector = identity.connectorByOcppExternalId(protocolConnectorId);
            return Integer.parseInt(connector.gunNo());
        } catch (RuntimeException e) {
            throw new OcppException(OcppConstants.ERROR_PROPERTY, e.getMessage());
        }
    }

    private static String platformGunNo(DeviceIdentityRegistry.Device identity, int connectorId) {
        if (connectorId <= 0) {
            return "00";
        }
        return identity.connectorByOcppExternalId(connectorId).gunNo();
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

    private MeterSample readMeterSample(JsonNode meterValue) {
        BigDecimal energyWh = null, voltage = null, current = null, powerKw = null, temperature = null;
        Integer soc = null;
        JsonNode sampledValues = meterValue.get("sampledValue");
        if (sampledValues != null && sampledValues.isArray()) {
            for (JsonNode sampled : sampledValues) {
                String measurand = text(sampled, "measurand", "Energy.Active.Import.Register");
                BigDecimal value = decimal(sampled, "value", null);
                if (value == null) continue;
                String unit = text(sampled, "unit", "");
                // A phase-specific value cannot represent the overall connector value.
                if (sampled.has("phase") && !sampled.get("phase").isNull()) continue;
                switch (measurand) {
                    case "Energy.Active.Import.Register" -> {
                        if (unit.isEmpty() || unit.equals("Wh") || unit.equals("kWh") || unit.equals("MWh"))
                            energyWh = toWh(value, unit);
                    }
                    case "Voltage" -> {
                        if (unit.isEmpty() || unit.equals("V")) voltage = value;
                    }
                    case "Current.Import" -> {
                        if (unit.isEmpty() || unit.equals("A")) current = value;
                    }
                    case "Power.Active.Import" -> {
                        if (unit.equals("kW")) powerKw = value;
                        else if (unit.isEmpty() || unit.equals("W")) powerKw = value.divide(THOUSAND);
                    }
                    case "Temperature" -> {
                        if (unit.equals("Celsius")) temperature = value;
                    }
                    case "SoC" -> {
                        if (unit.isEmpty() || unit.equals("Percent")) soc = value.intValue();
                    }
                    default -> {}
                }
            }
        }
        return new MeterSample(energyWh, voltage, current, soc, powerKw, temperature);
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

    private record MeterSample(
            BigDecimal energyWh,
            BigDecimal voltage,
            BigDecimal current,
            Integer soc,
            BigDecimal powerKw,
            BigDecimal temperature) {}
}
