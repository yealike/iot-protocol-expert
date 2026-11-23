package top.yealike.pep.ops.devices.thingmodel.domain;

public record DateTimeType(String format) implements ValueType {
    @Override
    public String typeId() {
        return "dateTime";
    }
}
