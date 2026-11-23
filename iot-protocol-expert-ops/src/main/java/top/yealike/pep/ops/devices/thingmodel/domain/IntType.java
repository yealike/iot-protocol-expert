package top.yealike.pep.ops.devices.thingmodel.domain;

public record IntType(Long min, Long max, String unit) implements ValueType {
    @Override
    public String typeId() {
        return "int";
    }
}
