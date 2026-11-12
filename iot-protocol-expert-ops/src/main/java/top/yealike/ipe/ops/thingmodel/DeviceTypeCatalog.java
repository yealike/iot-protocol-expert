package top.yealike.ipe.ops.thingmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 设备类型目录。
 *
 * <p>这里只声明「类型是什么」，不声明「类型能上报什么」——物模型正文在 {@code product.metadata} 里，支持运营期在线改。两者分开的原因： 类型决定分类、默认协议与 3D
 * 节点，属于代码契约；物模型属于业务数据。
 */
public enum DeviceTypeCatalog {
    CHARGER("充电桩", "charging", null, "YKC16", "CONNECTOR", "⚡");

    private final String label;
    private final String categoryCode;
    private final String sceneModel;
    private final String defaultProtocol;
    private final String channelType;
    private final String icon;

    DeviceTypeCatalog(
            String label,
            String categoryCode,
            String sceneModel,
            String defaultProtocol,
            String channelType,
            String icon) {
        this.label = label;
        this.categoryCode = categoryCode;
        this.sceneModel = sceneModel;
        this.defaultProtocol = defaultProtocol;
        this.channelType = channelType;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String categoryCode() {
        return categoryCode;
    }

    /** 3D 场景默认节点名，对应 energy-campus.glb 的根节点。 */
    public String sceneModel() {
        return sceneModel;
    }

    public String defaultProtocol() {
        return defaultProtocol;
    }

    /** 该类型设备的默认通道类型；null 表示没有通道。 */
    public String channelType() {
        return channelType;
    }

    public String icon() {
        return icon;
    }

    public boolean hasChannel() {
        return channelType != null;
    }

    public static Optional<DeviceTypeCatalog> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (DeviceTypeCatalog type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static DeviceTypeCatalog of(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("未知设备类型: " + code));
    }

    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deviceType", name());
        map.put("label", label);
        map.put("categoryCode", categoryCode);
        map.put("sceneModel", sceneModel);
        map.put("defaultProtocol", defaultProtocol);
        map.put("channelType", channelType);
        map.put("icon", icon);
        return map;
    }

    public static List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeviceTypeCatalog type : values()) {
            list.add(type.describe());
        }
        return list;
    }
}
