package top.yealike.ipe.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Import;
import top.yealike.ipe.protocol.telemetry.ObservabilityConfiguration;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(ObservabilityConfiguration.class)
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
