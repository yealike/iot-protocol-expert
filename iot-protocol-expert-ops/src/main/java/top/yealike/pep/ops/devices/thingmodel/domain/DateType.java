package top.yealike.pep.ops.devices.thingmodel.domain;

public record DateType(String format) implements ValueType {
    @Override
    public String typeId() {
        return "date";
    }
}
