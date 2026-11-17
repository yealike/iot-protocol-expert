package top.yealike.pep.simulator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.yealike.pep.gateway.telemetry.ObservabilityConfiguration;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(ObservabilityConfiguration.class)
@MapperScan("top.yealike.pep.simulator.mapper")
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
