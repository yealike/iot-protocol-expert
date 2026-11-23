package top.yealike.pep.ops.devices.thingmodel.domain;

public record LongType(Long min, Long max, String unit) implements ValueType {
    @Override
    public String typeId() {
        return "long";
    }
}
