package top.yealike.pep.ops.devices.thingmodel.domain;

public record StringType(Integer maxLength) implements ValueType {
    @Override
    public String typeId() {
        return "string";
    }
}
