package top.yealike.ipe.simulator.multidevice;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional anonymous loopback MQTT broker for local simulation only. */
@Component
public class LocalSimulationMqtt {
    private final boolean enabled;
    private final int port;
    private LocalMqttBroker broker;

    public LocalSimulationMqtt(
            @Value("${IPE_SIM_BROKER_ENABLED:false}") boolean enabled, @Value("${IPE_SIM_BROKER_PORT:1883}") int port) {
        this.enabled = enabled;
        this.port = port;
    }

    @PostConstruct
    public void start() throws Exception {
        if (enabled) broker = new LocalMqttBroker(port);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (broker != null) broker.close();
    }
}
