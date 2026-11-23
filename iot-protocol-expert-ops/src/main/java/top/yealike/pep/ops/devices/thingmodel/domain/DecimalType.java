package top.yealike.pep.ops.devices.thingmodel.domain;

public record DecimalType(Double min, Double max, String unit, Integer scale) implements ValueType {
    @Override
    public String typeId() {
        return "decimal";
    }
}
