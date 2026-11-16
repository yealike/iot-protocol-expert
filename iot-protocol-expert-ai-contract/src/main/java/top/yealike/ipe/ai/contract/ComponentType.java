package top.yealike.ipe.ai.contract;

/** 可注册组件类型。模型与凭据只存在于 Control，不进入注册中心。 */
public enum ComponentType {
    MCP("ipe-mcp"),
    SKILL("ipe-skill"),
    AGENT("ipe-agent");

    private final String servicePrefix;

    ComponentType(String servicePrefix) {
        this.servicePrefix = servicePrefix;
    }

    public String servicePrefix() {
        return servicePrefix;
    }
}
