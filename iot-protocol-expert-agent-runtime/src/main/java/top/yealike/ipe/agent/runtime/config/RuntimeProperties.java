package top.yealike.ipe.agent.runtime.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime 配置。密钥只从环境变量或挂载文件读取，不写入 Nacos、日志或响应。 */
@ConfigurationProperties(prefix = "ipe.runtime")
public record RuntimeProperties(
        int port,
        String controlBaseUrl,
        String serviceName,
        String serviceKeyId,
        String serviceKey,
        String serviceKeyFile,
        String controlServiceName,
        String advertiseIp,
        Nacos nacos,
        Run run,
        Mcp mcp,
        Skills skills,
        Observability observability) {

    public RuntimeProperties {
        port = port <= 0 ? 19084 : port;
        controlBaseUrl = blank(controlBaseUrl) ? "http://127.0.0.1:19083" : controlBaseUrl;
        controlBaseUrl = controlBaseUrl.strip();
        while (controlBaseUrl.endsWith("/")) {
            controlBaseUrl = controlBaseUrl.substring(0, controlBaseUrl.length() - 1);
        }
        serviceName = blank(serviceName) ? "runtime" : serviceName;
        serviceKeyId = blank(serviceKeyId) ? serviceName : serviceKeyId;
        controlServiceName = blank(controlServiceName) ? "control" : controlServiceName;
        nacos = nacos == null ? Nacos.defaults() : nacos;
        run = run == null ? Run.defaults() : run;
        mcp = mcp == null ? Mcp.defaults() : mcp;
        skills = skills == null ? Skills.defaults() : skills;
        observability = observability == null ? Observability.defaults() : observability;
    }

    /** 优先环境变量，其次只读秘密文件；文件内容不得被日志打印。 */
    public String resolvedServiceKey() {
        if (!blank(serviceKey)) {
            return serviceKey.strip();
        }
        if (!blank(serviceKeyFile)) {
            try {
                return Files.readString(Path.of(serviceKeyFile), StandardCharsets.UTF_8)
                        .strip();
            } catch (IOException | RuntimeException exception) {
                // 文件不可读时按未配置处理，由 readiness 明确下线；日志不输出路径或内容。
                return "";
            }
        }
        return "";
    }

    public boolean usable() {
        return !blank(controlBaseUrl) && !resolvedServiceKey().isBlank();
    }

    @Override
    public String toString() {
        return "RuntimeProperties[port=" + port
                + ", controlBaseUrl=" + controlBaseUrl
                + ", serviceName=" + serviceName
                + ", serviceKey=<redacted>"
                + ", serviceKeyFile=" + (blank(serviceKeyFile) ? "<empty>" : "<configured>")
                + ", controlServiceName=" + controlServiceName
                + ", advertiseIp=" + advertiseIp
                + ", nacos=" + nacos
                + "]";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Nacos(
            String serverAddr, String username, String password, String namespace, String group, boolean enabled) {

        public Nacos {
            serverAddr = blank(serverAddr) ? "127.0.0.1:8848" : serverAddr;
            namespace = blank(namespace) ? "public" : namespace;
            group = blank(group) ? "IPE_AI_COMPONENTS" : group;
        }

        static Nacos defaults() {
            return new Nacos("127.0.0.1:8848", "", "", "public", "IPE_AI_COMPONENTS", false);
        }

        @Override
        public String toString() {
            return "Nacos[serverAddr=" + serverAddr
                    + ", username=" + username
                    + ", password=<redacted>"
                    + ", namespace=" + namespace
                    + ", group=" + group
                    + ", enabled=" + enabled
                    + "]";
        }
    }

    public record Run(
            int maxActive,
            int queueCapacity,
            int maxIterationsHardLimit,
            int maxToolCallsHardLimit,
            long maxRunTimeoutMs,
            long heartbeatIntervalMs,
            long ownershipLossTimeoutMs) {

        public Run {
            maxActive = maxActive <= 0 ? 4 : maxActive;
            queueCapacity = queueCapacity < 0 ? 8 : queueCapacity;
            maxIterationsHardLimit = maxIterationsHardLimit <= 0 ? 12 : maxIterationsHardLimit;
            maxToolCallsHardLimit = maxToolCallsHardLimit <= 0 ? 32 : maxToolCallsHardLimit;
            maxRunTimeoutMs = maxRunTimeoutMs <= 0 ? 300_000L : maxRunTimeoutMs;
            heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 5_000L : heartbeatIntervalMs;
            ownershipLossTimeoutMs = ownershipLossTimeoutMs <= 0 ? 10_000L : ownershipLossTimeoutMs;
        }

        static Run defaults() {
            return new Run(4, 8, 12, 32, 300_000L, 5_000L, 10_000L);
        }
    }

    public record Mcp(
            int connectTimeoutMs,
            int initTimeoutMs,
            int callTimeoutMs,
            int maxConnections,
            int idleSeconds,
            int threads,
            int queueCapacity,
            int maxResultChars) {

        public Mcp {
            connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
            initTimeoutMs = initTimeoutMs <= 0 ? 10_000 : initTimeoutMs;
            callTimeoutMs = callTimeoutMs <= 0 ? 20_000 : callTimeoutMs;
            maxConnections = maxConnections <= 0 ? 32 : maxConnections;
            idleSeconds = idleSeconds <= 0 ? 300 : idleSeconds;
            threads = threads <= 0 ? 4 : threads;
            queueCapacity = queueCapacity < 0 ? 32 : queueCapacity;
            maxResultChars = maxResultChars <= 0 ? 65_536 : maxResultChars;
        }

        static Mcp defaults() {
            return new Mcp(3_000, 10_000, 20_000, 32, 300, 4, 32, 65_536);
        }
    }

    public record Skills(int maxReadChars) {

        public Skills {
            maxReadChars = maxReadChars <= 0 ? 12_000 : maxReadChars;
        }

        static Skills defaults() {
            return new Skills(12_000);
        }
    }

    public record Observability(Otel otel) {

        static Observability defaults() {
            return new Observability(Otel.defaults());
        }
    }

    public record Otel(boolean enabled, boolean contentEnabled, int maxContentChars) {

        static Otel defaults() {
            return new Otel(false, false, 8192);
        }
    }
}
