package top.yealike.pep.modelgateway.catalog.domain;

import java.util.UUID;

/** 逻辑模型目录行；Agent 只引用 key，不接触上游模型名与供应商密钥。 */
public record ModelProfile(
        UUID id,
        String key,
        String displayName,
        UUID providerId,
        String upstreamModel,
        Integer contextWindow,
        int maxOutputTokens,
        int tokenWeightNum,
        int tokenWeightDen,
        String countingMode,
        String state) {}
