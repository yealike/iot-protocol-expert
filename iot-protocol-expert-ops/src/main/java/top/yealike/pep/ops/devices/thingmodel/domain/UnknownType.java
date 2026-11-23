package top.yealike.pep.ops.devices.thingmodel.domain;

/** 未知类型兜底：仍可存取，但不做范围校验。 */
public record UnknownType(String raw) implements ValueType {
    @Override
    public String typeId() {
        return raw == null || raw.isBlank() ? "unknown" : raw;
    }
}
