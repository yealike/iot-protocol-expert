package top.yealike.pep.runtime.config.properties;

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
        NacosProperties nacos,
        RunProperties run,
        McpProperties mcp,
        SkillProperties skills,
        ObservabilityProperties observability) {

    public RuntimeProperties {
        port = port <= 0 ? 19084 : port;
        controlBaseUrl = blank(controlBaseUrl) ? "http://127.0.0.1:19083" : controlBaseUrl;
        controlBaseUrl = controlBaseUrl.strip();
        while (controlBaseUrl.endsWith("/")) {
            controlBaseUrl = controlBaseUrl.substring(0, controlBaseUrl.length() - 1);
        }
        serviceName = blank(serviceName) ? "runtime" : serviceName;
        serviceKeyId = blank(serviceKeyId) ? serviceName : serviceKeyId;
        controlServiceName = blank(controlServiceName) ? "ipe-ai-control" : controlServiceName;
        nacos = nacos == null ? NacosProperties.defaults() : nacos;
        run = run == null ? RunProperties.defaults() : run;
        mcp = mcp == null ? McpProperties.defaults() : mcp;
        skills = skills == null ? SkillProperties.defaults() : skills;
        observability = observability == null ? ObservabilityProperties.defaults() : observability;
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
}
