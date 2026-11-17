package top.yealike.pep.modelgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 唯一模型出口：验权、配额预占/结算、供应商路由与调用审计。 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PepModelGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PepModelGatewayApplication.class, args);
    }
}
