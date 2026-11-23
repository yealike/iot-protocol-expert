package top.yealike.pep.modelgateway.config.properties;

/** 输入 token 的保守估算参数；不是精确 tokenizer。 */
public record GatewayCounting(String charsPerToken, int messageOverhead, int requestOverhead, boolean strict) {

    public GatewayCounting {
        charsPerToken = charsPerToken == null || charsPerToken.isBlank() ? "0.5" : charsPerToken;
        messageOverhead = messageOverhead <= 0 ? 8 : messageOverhead;
        requestOverhead = requestOverhead <= 0 ? 256 : requestOverhead;
    }

    public static GatewayCounting defaults() {
        return new GatewayCounting("0.5", 8, 256, false);
    }
}
