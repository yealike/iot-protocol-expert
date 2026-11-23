package top.yealike.pep.runtime.config.properties;

/** AgentScope OTel 导出配置；contentEnabled 默认关闭，避免导出 prompt/response 正文。 */
public record OtelProperties(boolean enabled, boolean contentEnabled, int maxContentChars) {

    static OtelProperties defaults() {
        return new OtelProperties(false, false, 8192);
    }
}
