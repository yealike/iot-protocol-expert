package top.yealike.pep.control.config.properties;

public record PlatformRegistry(
        boolean enabled,
        String serverAddr,
        String username,
        String password,
        String namespace,
        String group,
        String advertiseIp,
        String controlServiceName,
        String mcpGatewayBase,
        int cacheSeconds) {}
