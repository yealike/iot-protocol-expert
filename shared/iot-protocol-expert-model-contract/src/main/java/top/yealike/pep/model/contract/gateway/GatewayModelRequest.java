package top.yealike.pep.model.contract.gateway;

/** 登记逻辑模型的请求体。 */
public record GatewayModelRequest(
        String key,
        String displayName,
        String providerCode,
        String upstreamModel,
        Integer contextWindow,
        Integer maxOutputTokens,
        String countingMode) {}
