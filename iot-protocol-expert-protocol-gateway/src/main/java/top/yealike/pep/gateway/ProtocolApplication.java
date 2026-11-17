package top.yealike.pep.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.context.annotation.Import(top.yealike.pep.observability.protocol.ObservabilityConfiguration.class)
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ProtocolApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProtocolApplication.class, args);
    }
}
