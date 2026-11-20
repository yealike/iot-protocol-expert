package top.yealike.pep.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.yealike.pep.observability.LangfuseTelemetryConfiguration;
import top.yealike.pep.observability.protocol.LogbackTelemetryConfiguration;

/** 独立 Agent Runtime：仅执行 Run，不提供浏览器入口，也不直接访问数据库。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import({LogbackTelemetryConfiguration.class, LangfuseTelemetryConfiguration.class})
public class AgentRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRuntimeApplication.class, args);
    }
}
