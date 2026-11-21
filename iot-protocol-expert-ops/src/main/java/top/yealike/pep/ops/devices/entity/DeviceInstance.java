package top.yealike.pep.ops.devices.entity;

import java.time.LocalDateTime;
import java.util.Locale;

/** 充电桩实例。{@code deviceCode} 是平台 8 位设备 SN，同时作为桩号。 */
public class DeviceInstance {

    public static final String STATE_NOT_ACTIVE = "NOT_ACTIVE";
    public static final String STATE_OFFLINE = "OFFLINE";
    public static final String STATE_ONLINE = "ONLINE";
    public static final String STATE_FAULT = "FAULT";

    public Long id;
    public String deviceCode;
    public String name;
    public Long productId;

    /** 冗余自产品，产品创建后协议不可切换。 */
    public String protocol;

    public String state = STATE_NOT_ACTIVE;
    public boolean enabled = true;
    public boolean allowSimulate = true;
    public boolean allowWork = true;
    public String configuration;
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
