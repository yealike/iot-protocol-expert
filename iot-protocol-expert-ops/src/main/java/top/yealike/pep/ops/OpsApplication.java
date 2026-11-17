package top.yealike.pep.ops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 运营服务入口；持有用户会话、设备业务和历史数据库迁移。 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan({
    "top.yealike.pep.ops.access.mapper",
    "top.yealike.pep.ops.alarms.mapper",
    "top.yealike.pep.ops.audit.mapper",
    "top.yealike.pep.ops.charging.mapper",
    "top.yealike.pep.ops.devices.mapper",
    "top.yealike.pep.ops.diagnostic.mapper",
    "top.yealike.pep.ops.telemetry.mapper"
})
public class OpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
