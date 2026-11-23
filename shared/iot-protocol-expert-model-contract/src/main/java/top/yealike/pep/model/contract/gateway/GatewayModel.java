package top.yealike.pep.model.contract.gateway;

import java.util.UUID;

/** 模型网关逻辑模型管理视图。 */
public record GatewayModel(
        UUID id,
        String key,
        String displayName,
        UUID providerId,
        String upstreamModel,
        Integer contextWindow,
        int maxOutputTokens,
        String countingMode,
        String state) {}
