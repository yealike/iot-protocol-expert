package top.yealike.pep.control;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.yealike.pep.observability.LangfuseTelemetryConfiguration;
import top.yealike.pep.observability.api.ApiResponseAdvice;
import top.yealike.pep.observability.protocol.LogbackTelemetryConfiguration;

/** Agent 控制面入口：组件版本、发布与任务编排。 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@Import({LogbackTelemetryConfiguration.class, LangfuseTelemetryConfiguration.class, ApiResponseAdvice.class})
@MapperScan({
    "top.yealike.pep.control.access.policy.mapper",
    "top.yealike.pep.control.access.mapper",
    "top.yealike.pep.control.agents.mapper",
    "top.yealike.pep.control.api.internal.mapper",
    "top.yealike.pep.control.audit",
    "top.yealike.pep.control.conversations.mapper",
    "top.yealike.pep.control.knowledge.mapper",
    "top.yealike.pep.control.runs.mapper"
})
public class AgentControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentControlApplication.class, args);
    }
}
