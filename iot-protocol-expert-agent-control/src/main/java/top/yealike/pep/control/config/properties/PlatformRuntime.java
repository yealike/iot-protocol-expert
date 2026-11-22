package top.yealike.pep.control.config.properties;

public record PlatformRuntime(
        int maxDispatchAttempts,
        int leaseSeconds,
        int queueLimit,
        int perUserQueued,
        int perUserInFlight,
        String baseUrl) {}
