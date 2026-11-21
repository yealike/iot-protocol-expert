package top.yealike.pep.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@org.springframework.context.annotation.Import(top.yealike.pep.observability.protocol.ObservabilityConfiguration.class)
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProtocolApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProtocolApplication.class, args);
    }
}
