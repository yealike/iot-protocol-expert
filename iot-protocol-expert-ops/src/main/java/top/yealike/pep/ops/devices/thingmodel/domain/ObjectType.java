package top.yealike.pep.ops.devices.thingmodel.domain;

import java.util.List;

public record ObjectType(List<PropertyMetadata> properties) implements ValueType {
    @Override
    public String typeId() {
        return "object";
    }
}
