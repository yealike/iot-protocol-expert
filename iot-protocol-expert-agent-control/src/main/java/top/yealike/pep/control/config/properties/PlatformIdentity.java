package top.yealike.pep.control.config.properties;

public record PlatformIdentity(
        String serviceName, String namespace, String group, String baseUrl, String serviceToken) {}
