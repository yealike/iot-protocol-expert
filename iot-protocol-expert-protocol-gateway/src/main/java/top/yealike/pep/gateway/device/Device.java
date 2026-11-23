package top.yealike.pep.gateway.device;

import java.util.Map;
import top.yealike.pep.gateway.protocol.ProtocolType;

/** 已登记的充电桩身份及枪口映射。 */
public record Device(
        ProtocolType protocol,
        String deviceSn,
        String thirdPartyDeviceId,
        boolean enabled,
        Map<String, Connector> connectors) {

    public int connectorCount() {
        return connectors.size();
    }

    public Connector connectorByExternalNo(String externalConnectorNo) {
        String key = normalizeConnectorNo(externalConnectorNo);
        Connector connector = connectors.get(key);
        if (connector == null) {
            throw new IllegalStateException("第三方枪号未登记：" + thirdPartyDeviceId + "/" + externalConnectorNo);
        }
        return connector;
    }

    public Connector connectorByPlatformGunNo(String platformGunNo) {
        String value = requireText(platformGunNo, "平台枪号");
        if (value.matches("[0-9]{2}")) {
            int gun = Integer.parseInt(value);
            if (gun < 1 || gun > 99) {
                throw new IllegalStateException("平台枪号必须为 01~99：" + value);
            }
            value = deviceSn + value;
        }
        for (Connector connector : connectors.values()) {
            if (connector.platformGunNo().equals(value)) {
                return connector;
            }
        }
        throw new IllegalStateException("平台枪号不属于设备：" + deviceSn + "/" + platformGunNo);
    }

    public Connector connectorByOcppExternalId(int connectorId) {
        return connectorByExternalNo(String.valueOf(connectorId));
    }

    private static String normalizeConnectorNo(String value) {
        String normalized = requireText(value, "第三方枪号").trim();
        if (!normalized.matches("[0-9]{1,2}")) {
            throw new IllegalStateException("第三方枪号必须为 1~99：" + value);
        }
        int number = Integer.parseInt(normalized);
        if (number < 1 || number > 99) {
            throw new IllegalStateException("第三方枪号必须为 1~99：" + value);
        }
        return String.format("%02d", number);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + "不能为空");
        }
        return value;
    }
}
