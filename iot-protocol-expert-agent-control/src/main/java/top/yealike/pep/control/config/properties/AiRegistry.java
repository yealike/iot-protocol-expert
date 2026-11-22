package top.yealike.pep.control.config.properties;

/**
 * Nacos MCP Registry 目录。启用后由 {@code gateway-base-url} 与服务名拼出入口，并校验目录中的版本与工具清单。
 */
public record AiRegistry(boolean enabled, String serverName, String version, String namespace) {

    public boolean usable() {
        return enabled && serverName != null && !serverName.isBlank();
    }
}
