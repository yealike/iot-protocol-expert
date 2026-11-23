package top.yealike.pep.ops.devices.thingmodel.domain;

public record GeoType() implements ValueType {
    @Override
    public String typeId() {
        return "geo";
    }
}
