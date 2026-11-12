package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Locale;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

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
@Entity
@Table(name = "device_instance")
public class DeviceInstance {

    public static final String STATE_NOT_ACTIVE = "NOT_ACTIVE";
    public static final String STATE_OFFLINE = "OFFLINE";
    public static final String STATE_ONLINE = "ONLINE";
    public static final String STATE_FAULT = "FAULT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_code", nullable = false, unique = true, length = 64)
    public String deviceCode;

    @Column(nullable = false, length = 128)
    public String name;

    @Column(name = "product_id", nullable = false)
    public Long productId;

    @Column(name = "device_type", nullable = false, length = 32)
    public String deviceType;

    @Column(nullable = false, length = 32)
    public String protocol;

    @Column(name = "protocol_device_id", nullable = false, length = 64)
    public String protocolDeviceId;

    @Column(name = "station_id")
    public Long stationId;

    /** 网关子设备的父设备。 */
    @Column(name = "parent_id")
    public Long parentId;

    @Column(nullable = false, length = 16)
    public String state = STATE_NOT_ACTIVE;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "allow_simulate", nullable = false)
    public boolean allowSimulate = true;

    /** 是否允许远程启停；对应物模型里的 allowWork 属性，这里保留一份用于快速拒绝建单。 */
    @Column(name = "allow_work", nullable = false)
    public boolean allowWork = true;

    /** 设备级物模型覆盖；空表示完全继承产品。 */
    @Type(JsonbStringType.class)
    @Column(name = "derive_metadata", columnDefinition = "jsonb")
    public String deriveMetadata;

    @Type(JsonbStringType.class)
    @Column(columnDefinition = "jsonb")
    public String configuration;

    /** {"model":"PV","position":[-4.4,2.3,-6.3],"scale":1} */
    @Type(JsonbStringType.class)
    @Column(name = "scene_anchor", columnDefinition = "jsonb")
    public String sceneAnchor;

    @Column(name = "session_id", length = 64)
    public String sessionId;

    @Column(name = "last_seen")
    public LocalDateTime lastSeen;

    @Column(name = "online_at")
    public LocalDateTime onlineAt;

    @Column(name = "registry_time")
    public LocalDateTime registryTime;

    @Column(length = 256)
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
