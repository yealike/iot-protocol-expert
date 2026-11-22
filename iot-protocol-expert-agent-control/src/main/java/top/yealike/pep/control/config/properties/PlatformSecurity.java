package top.yealike.pep.control.config.properties;

public record PlatformSecurity(
        String masterKey,
        String masterKeyFile,
        String internalServiceName,
        String runtimeServiceName,
        String internalServiceKey,
        String internalKeyId,
        int clockSkewSeconds) {}
