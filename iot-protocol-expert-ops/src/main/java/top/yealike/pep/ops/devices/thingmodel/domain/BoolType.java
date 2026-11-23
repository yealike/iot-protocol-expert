package top.yealike.pep.ops.devices.thingmodel.domain;

public record BoolType() implements ValueType {
    @Override
    public String typeId() {
        return "bool";
    }
}
