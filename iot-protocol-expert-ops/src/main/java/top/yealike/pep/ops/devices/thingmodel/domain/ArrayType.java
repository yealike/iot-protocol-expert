package top.yealike.pep.ops.devices.thingmodel.domain;

public record ArrayType(ValueType elementType) implements ValueType {
    @Override
    public String typeId() {
        return "array";
    }
}
