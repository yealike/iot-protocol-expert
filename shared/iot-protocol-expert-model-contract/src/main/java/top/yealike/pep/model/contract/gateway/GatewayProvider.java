package top.yealike.pep.model.contract.gateway;

import java.util.UUID;

/** 模型供应商管理视图；不包含供应商密钥。 */
public record GatewayProvider(
        UUID id, String code, String name, String baseUrl, String state, boolean credentialConfigured) {}
