package top.yealike.pep.model.contract;

/** 网关公开路径。Runtime 只使用 OpenAI 兼容入口。 */
public final class ModelGatewayPaths {

    public static final String CHAT_COMPLETIONS = "/v1/chat/completions";
    public static final String ME_QUOTA = "/api/model-gateway/v1/me/quota";
    public static final String ME_USAGE = "/api/model-gateway/v1/me/usage";

    private ModelGatewayPaths() {}
}
