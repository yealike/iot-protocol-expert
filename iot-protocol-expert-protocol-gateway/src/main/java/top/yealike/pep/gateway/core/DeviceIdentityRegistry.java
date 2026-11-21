package top.yealike.pep.gateway.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.yealike.pep.gateway.ops.OpsClient;
import top.yealike.pep.gateway.ops.OpsDtos;

/**
 * 启动期从 Ops 加载的充电桩身份索引。
 *
 * <p>协议线上只出现第三方设备号和枪号，业务调用统一使用平台 8 位 SN 与 10 位平台枪号。
 * 索引加载完成后不再变化，设备连接期间不会访问 Ops 补录身份。
 */
@Component
public final class DeviceIdentityRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityRegistry.class);
    private static final int PAGE_SIZE = 500;

    private final OpsClient ops;
    private volatile Snapshot snapshot;

    public DeviceIdentityRegistry(OpsClient ops) {
        this.ops = ops;
    }

    /** 完整加载并校验所有分页，任一异常都会阻止协议端口启动。 */
    public void load() {
        long afterId = 0;
        Set<Long> cursors = new HashSet<>();
        Set<String> deviceSns = new HashSet<>();
        Set<Key> externalKeys = new HashSet<>();
        Set<String> platformGunNumbers = new HashSet<>();
        Map<String, Device> byDeviceSn = new LinkedHashMap<>();
        Map<Key, Device> byExternal = new LinkedHashMap<>();
        int mappingCount = 0;
        String snapshotToken = null;

        while (true) {
            OpsDtos.DeviceMappingPage page = ops.deviceMappings(afterId, PAGE_SIZE);
            if (page == null || page.content() == null) {
                throw new IllegalStateException("Ops 返回的设备映射分页为空");
            }
            if (page.snapshotToken() == null || page.snapshotToken().isBlank()) {
                throw new IllegalStateException("Ops 未返回设备映射快照标识");
            }
            if (snapshotToken == null) {
                snapshotToken = page.snapshotToken();
            } else if (!snapshotToken.equals(page.snapshotToken())) {
                throw new IllegalStateException("设备映射在加载过程中发生变化，请重启 protocol-gateway");
            }
            for (OpsDtos.DeviceMappingRow row : page.content()) {
                Device device = validate(row);
                mappingCount++;

                if (!deviceSns.add(device.deviceSn())) {
                    throw new IllegalStateException("设备 SN 重复映射：" + device.deviceSn());
                }
                Key externalKey = new Key(device.protocol(), device.thirdPartyDeviceId());
                if (!externalKeys.add(externalKey)) {
                    throw new IllegalStateException(
                            "第三方设备号重复映射：" + device.protocol().code() + "/" + device.thirdPartyDeviceId());
                }
                for (Connector connector : device.connectors().values()) {
                    if (!platformGunNumbers.add(connector.platformGunNo())) {
                        throw new IllegalStateException("平台枪号重复映射：" + connector.platformGunNo());
                    }
                }

                if (device.enabled()) {
                    byDeviceSn.put(device.deviceSn(), device);
                    byExternal.put(externalKey, device);
                }
            }

            if (!page.hasMore()) {
                break;
            }
            Long nextAfterId = page.nextAfterId();
            if (nextAfterId == null || nextAfterId <= afterId || !cursors.add(nextAfterId)) {
                throw new IllegalStateException("Ops 设备映射分页游标异常：" + nextAfterId);
            }
            afterId = nextAfterId;
        }

        snapshot = new Snapshot(
                Collections.unmodifiableMap(new LinkedHashMap<>(byDeviceSn)),
                Collections.unmodifiableMap(new LinkedHashMap<>(byExternal)));
        log.info("设备身份映射已加载：{} 条映射，{} 个启用设备", mappingCount, byExternal.size());
    }

    /** 协议命令兼容入口：平台 SN 优先，其次按第三方设备号精确匹配。 */
    public Device resolve(ProtocolType protocol, String identifier) {
        Objects.requireNonNull(protocol, "protocol");
        String value = requireText(identifier, "设备编号");
        Snapshot current = loaded();
        if (value.matches("[1-9][0-9]{7}")) {
            return resolvePlatform(protocol, value);
        }
        Device external = current.byExternal().get(new Key(protocol, value));
        if (external != null) {
            return external;
        }
        return resolvePlatform(protocol, value);
    }

    /** 协议握手只能使用第三方设备号，避免第三方数字号与平台 SN/枪号互相误判。 */
    public Device resolveExternal(ProtocolType protocol, String thirdPartyDeviceId) {
        Objects.requireNonNull(protocol, "protocol");
        String value = requireText(thirdPartyDeviceId, "第三方设备号");
        Device device = loaded().byExternal().get(new Key(protocol, value));
        if (device == null) {
            throw new IllegalStateException("第三方设备未登记：" + protocol.code() + "/" + value);
        }
        return device;
    }

    /** 平台内部调用只接受 8 位设备 SN 或 10 位平台枪号。 */
    public Device resolvePlatform(ProtocolType protocol, String identifier) {
        Objects.requireNonNull(protocol, "protocol");
        String value = requireText(identifier, "平台设备编号");
        Snapshot current = loaded();
        if (value.matches("[1-9][0-9]{7}")) {
            Device device = current.byDeviceSn().get(value);
            if (device == null) {
                throw new IllegalStateException("设备未登记：" + value);
            }
            requireProtocol(device, protocol);
            return device;
        }
        if (value.matches("[1-9][0-9]{9}")) {
            Device device = current.byDeviceSn().get(value.substring(0, 8));
            if (device == null) {
                throw new IllegalStateException("设备未登记：" + value.substring(0, 8));
            }
            requireProtocol(device, protocol);
            device.connectorByPlatformGunNo(value);
            return device;
        }
        throw new IllegalStateException("平台设备编号必须为 8 位 SN 或 10 位平台枪号：" + value);
    }

    private Snapshot loaded() {
        Snapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("设备身份映射尚未加载");
        }
        return current;
    }

    private static Device validate(OpsDtos.DeviceMappingRow row) {
        if (row == null) {
            throw new IllegalStateException("Ops 返回了空设备映射");
        }
        String deviceSn = requireText(row.deviceSn(), "设备 SN");
        if (!deviceSn.matches("[1-9][0-9]{7}")) {
            throw new IllegalStateException("设备 SN 必须为 8 位数字且从 10000000 开始：" + deviceSn);
        }
        String externalId = requireText(row.thirdPartyDeviceId(), "第三方设备号");
        if (externalId.matches("[1-9][0-9]{7}")) {
            throw new IllegalStateException("第三方设备号不能使用 8 位平台 SN 格式：" + externalId);
        }
        ProtocolType protocol = ProtocolType.of(requireText(row.protocol(), "协议"));
        if (protocol == ProtocolType.YKC16 && !externalId.matches("[0-9]{14}")) {
            throw new IllegalStateException("云快充第三方设备号必须为 14 位数字：" + externalId);
        }
        if (row.connectors() == null || row.connectors().isEmpty()) {
            throw new IllegalStateException("设备没有枪口映射：" + deviceSn);
        }

        Map<String, Connector> connectors = new LinkedHashMap<>();
        for (OpsDtos.DeviceMappingConnector value : row.connectors()) {
            if (value == null) {
                throw new IllegalStateException("设备包含空枪口映射：" + deviceSn);
            }
            String platformGunNo = requireText(value.platformGunNo(), "平台枪号");
            if (!platformGunNo.matches("[1-9][0-9]{7}(0[1-9]|[1-9][0-9])") || !platformGunNo.startsWith(deviceSn)) {
                throw new IllegalStateException("平台枪号必须为设备 SN 加两位枪号：" + deviceSn + "/" + platformGunNo);
            }
            String protocolConnectorNo = normalizeConnectorNo(value.protocolConnectorNo());
            Connector previous = connectors.put(
                    protocolConnectorNo,
                    new Connector(
                            platformGunNo,
                            platformGunNo.substring(8),
                            protocolConnectorNo,
                            Integer.parseInt(protocolConnectorNo)));
            if (previous != null) {
                throw new IllegalStateException("设备第三方枪号重复：" + deviceSn + "/" + protocolConnectorNo);
            }
        }
        return new Device(
                protocol,
                deviceSn,
                externalId,
                row.enabled(),
                Collections.unmodifiableMap(new LinkedHashMap<>(connectors)));
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

    private static void requireProtocol(Device device, ProtocolType protocol) {
        if (device.protocol() != protocol) {
            throw new IllegalStateException("设备协议不匹配：平台 " + device.deviceSn() + " 为 "
                    + device.protocol().code() + "，连接协议为 " + protocol.code());
        }
    }

    public record Connector(String platformGunNo, String gunNo, String protocolConnectorNo, int connectorOrdinal) {

        public String externalYkcGunNo() {
            return protocolConnectorNo;
        }

        public int externalOcppConnectorId() {
            return connectorOrdinal;
        }
    }

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
    }

    private record Key(ProtocolType protocol, String thirdPartyDeviceId) {}

    private record Snapshot(Map<String, Device> byDeviceSn, Map<Key, Device> byExternal) {}
}
