package top.yealike.pep.ops.devices.entity;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 设备实例。
 *
 * <p>字段取舍：
 *
 * <ul>
 *   <li>{@code deviceCode} 是平台侧唯一编码（原 {@code sn}），协议侧编号是 {@code protocolDeviceId}，
 *       二者只在各自范围内唯一；同一台物理设备换协议不换 {@code deviceCode}。
 *   <li>{@code deviceType}、{@code protocol} 冗余自产品：列表页与过滤不走 join。 产品改协议时由服务层同步刷新。
 *   <li>协议专属字段（桩类型、SIM 卡号、厂商型号、连接器数）不再进主表，走 {@link DeviceTag}。
 *   <li>{@code state} 四值：NOT_ACTIVE / OFFLINE / ONLINE / FAULT。不再单独存 online 布尔。
 * </ul>
 */
public class DeviceInstance {

    public static final String STATE_NOT_ACTIVE = "NOT_ACTIVE";
    public static final String STATE_OFFLINE = "OFFLINE";
    public static final String STATE_ONLINE = "ONLINE";
    public static final String STATE_FAULT = "FAULT";

    public Long id;

    public String deviceCode;

    public String name;

    public Long productId;

    public String deviceType;

    public String protocol;

    public String protocolDeviceId;

    public Long stationId;

    /** 网关子设备的父设备。 */
    public Long parentId;

    public String state = STATE_NOT_ACTIVE;

    public boolean enabled = true;

    public boolean allowSimulate = true;

    /** 是否允许远程启停；对应物模型里的 allowWork 属性，这里保留一份用于快速拒绝建单。 */
    public boolean allowWork = true;

    /** 设备级物模型覆盖；空表示完全继承产品。 */
    public String deriveMetadata;

    public String configuration;

    /** {"model":"PV","position":[-4.4,2.3,-6.3],"scale":1} */
    public String sceneAnchor;

    public String sessionId;

    public LocalDateTime lastSeen;

    public LocalDateTime onlineAt;

    public LocalDateTime registryTime;

    public String remark;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public boolean isOnline() {
        return STATE_ONLINE.equals(state) || STATE_FAULT.equals(state);
    }

    public String getStateOrOffline() {
        return state == null ? STATE_OFFLINE : state;
    }

    public static String normalizeState(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATE_NOT_ACTIVE;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case STATE_ONLINE, STATE_OFFLINE, STATE_FAULT, STATE_NOT_ACTIVE -> value;
            default -> throw new IllegalArgumentException("未知设备状态: " + raw);
        };
    }
}
