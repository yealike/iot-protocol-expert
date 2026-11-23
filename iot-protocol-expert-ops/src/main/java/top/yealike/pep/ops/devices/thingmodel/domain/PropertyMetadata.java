package top.yealike.pep.ops.devices.thingmodel.domain;

import java.util.Map;

/**
 * 属性。{@code channel=true} 表示按通道上报（枪电压、组串电流、电池簇 SOC）， 存储键为 (deviceId, channelNo, propertyId)。
 */
public record PropertyMetadata(
        String id,
        String name,
        ValueType valueType,
        boolean channel,
        boolean readOnly,
        String description,
        Map<String, Object> expands) {
    public PropertyMetadata {
        expands = expands == null ? Map.of() : Map.copyOf(expands);
    }

    public static PropertyMetadata of(String id, String name, ValueType valueType) {
        return new PropertyMetadata(id, name, valueType, false, true, null, Map.of());
    }

    public static PropertyMetadata writable(String id, String name, ValueType valueType) {
        return new PropertyMetadata(id, name, valueType, false, false, null, Map.of());
    }

    public static PropertyMetadata channel(String id, String name, ValueType valueType) {
        return new PropertyMetadata(id, name, valueType, true, true, null, Map.of());
    }

    public PropertyMetadata withChannel(boolean value) {
        return new PropertyMetadata(id, name, valueType, value, readOnly, description, expands);
    }
}
