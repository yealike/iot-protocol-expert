package top.yealike.pep.runtime.contract;

import java.util.List;

/** 随 Release 冻结的能力声明。 */
public record A2aCapability(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes) {

    public A2aCapability {
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        inputModes = inputModes == null ? List.of() : List.copyOf(inputModes);
        outputModes = outputModes == null ? List.of() : List.copyOf(outputModes);
    }
}
