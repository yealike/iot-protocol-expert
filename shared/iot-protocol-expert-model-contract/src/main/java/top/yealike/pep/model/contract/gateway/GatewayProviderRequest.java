package top.yealike.pep.model.contract.gateway;

/** 登记模型供应商的请求体；apiKey 只写不读。 */
public record GatewayProviderRequest(String code, String name, String baseUrl, String apiKey) {}
