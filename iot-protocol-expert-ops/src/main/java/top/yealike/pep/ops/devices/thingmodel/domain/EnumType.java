package top.yealike.pep.ops.devices.thingmodel.domain;

import java.util.List;

public record EnumType(List<EnumElement> elements) implements ValueType {
    @Override
    public String typeId() {
        return "enum";
    }

    public boolean accepts(String value) {
        return elements.stream().anyMatch(e -> e.value().equals(value));
    }

    public String textOf(String value) {
        return elements.stream()
                .filter(e -> e.value().equals(value))
                .map(EnumElement::text)
                .findFirst()
                .orElse(value);
    }
}
