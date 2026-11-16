package top.yealike.ipe.ops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("top.yealike.ipe.ops.mapper")
public class OpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
