package top.yealike.ipe.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 助手与 Agent 能力平台入口。
 *
 * <p>本服务只负责身份、运行时策略、Agent 编排、会话与知识检索；业务数据一律经 ops 内部只读接口获取，不直接访问业务表。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
