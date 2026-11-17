package top.yealike.pep.control;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 协议专家控制服务入口。
 *
 * <p>按 Spring Modulith 思路组织的模块化单体：身份权限、租户项目、设备与充电交易、遥测投影、
 * 调查任务、Agent 目录发布、知识与评测共享一个进程和数据库连接，模块间只通过公开应用服务协作。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan({
    "top.yealike.pep.control.access.mapper",
    "top.yealike.pep.control.agents.mapper",
    "top.yealike.pep.control.alarms.mapper",
    "top.yealike.pep.control.audit.mapper",
    "top.yealike.pep.control.charging.mapper",
    "top.yealike.pep.control.conversations.mapper",
    "top.yealike.pep.control.devices.mapper",
    "top.yealike.pep.control.diagnostic.mapper",
    "top.yealike.pep.control.evaluations.mapper",
    "top.yealike.pep.control.knowledge.mapper",
    "top.yealike.pep.control.telemetry.mapper"
})
public class PepControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(PepControlApplication.class, args);
    }
}
