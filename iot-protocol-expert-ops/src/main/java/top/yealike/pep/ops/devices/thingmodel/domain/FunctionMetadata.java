package top.yealike.pep.ops.devices.thingmodel.domain;

import java.util.List;

/** 功能（指令）。inputs/output 复用同一套值类型。 */
public record FunctionMetadata(
        String id, String name, boolean async, List<PropertyMetadata> inputs, ValueType output, String description) {
    public FunctionMetadata {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
