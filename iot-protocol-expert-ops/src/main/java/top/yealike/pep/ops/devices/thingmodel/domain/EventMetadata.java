package top.yealike.pep.ops.devices.thingmodel.domain;

/** 事件。level 是事件级别。 */
public record EventMetadata(String id, String name, String level, ValueType valueType, String description) {
    public String levelOrInfo() {
        return level == null || level.isBlank() ? "INFO" : level;
    }
}
