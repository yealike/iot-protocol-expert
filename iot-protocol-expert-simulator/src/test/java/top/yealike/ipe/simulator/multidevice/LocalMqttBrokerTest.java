package top.yealike.ipe.simulator.multidevice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.collector.MqttCollector;
import top.yealike.ipe.protocol.ops.OpsClient;

class LocalMqttBrokerTest {
    @TempDir
    Path dir;

    @Test
    void simulatorPublishesThroughLocalBrokerToCollector() throws Exception {
        try (LocalMqttBroker broker = new LocalMqttBroker(0);
                ServerSocket available = new ServerSocket(0)) {
            int modbusPort = available.getLocalPort();
            available.close();
            Path config = dir.resolve("mqtt.json");
            Files.writeString(
                    config,
                    "{\"broker\":{\"host\":\"127.0.0.1\",\"port\":" + broker.port()
                            + ",\"tls\":false,\"clientId\":\"collector-test\"},\"devices\":[{\"topic\":\"ipe/simulation/SIM-LOCK-001/properties\",\"protocolDeviceId\":\"SIM-LOCK-001\",\"properties\":{\"state\":\"state\"}}]}");
            var ops = mock(OpsClient.class);
            CountDownLatch received = new CountDownLatch(1);
            doAnswer(inv -> {
                        received.countDown();
                        return 1;
                    })
                    .when(ops)
                    .properties(eq("MQTT"), eq("SIM-LOCK-001"), eq(""), anyMap());
            var mapper = new ObjectMapper();
            var collector = new MqttCollector(ops, mapper, config.toString(), "", "");
            var simulator = new MultiDeviceRuntime(true, modbusPort, "tcp://127.0.0.1:" + broker.port(), mapper);
            try {
                collector.start();
                simulator.start();
                for (int i = 0; i < 20 && received.getCount() > 0; i++) {
                    simulator.publish();
                    received.await(200, TimeUnit.MILLISECONDS);
                }
                assertEquals(0, received.getCount());
            } finally {
                simulator.stop();
                collector.stop();
            }
        }
    }

    @Test
    void collectorRecoversWhenBrokerStartsLater() throws Exception {
        int brokerPort;
        try (ServerSocket free = new ServerSocket(0)) {
            brokerPort = free.getLocalPort();
        }
        Path config = dir.resolve("later.json");
        Files.writeString(
                config,
                "{\"broker\":{\"host\":\"127.0.0.1\",\"port\":" + brokerPort
                        + ",\"tls\":false,\"clientId\":\"collector-later\"},\"devices\":[{\"topic\":\"ipe/simulation/SIM-LOCK-001/properties\",\"protocolDeviceId\":\"SIM-LOCK-001\",\"properties\":{\"state\":\"state\"}}]}");
        var ops = mock(OpsClient.class);
        CountDownLatch received = new CountDownLatch(1);
        doAnswer(inv -> {
                    received.countDown();
                    return 1;
                })
                .when(ops)
                .properties(eq("MQTT"), eq("SIM-LOCK-001"), eq(""), anyMap());
        var mapper = new ObjectMapper();
        var collector = new MqttCollector(ops, mapper, config.toString(), "", "");
        collector.start();
        try (LocalMqttBroker broker = new LocalMqttBroker(brokerPort);
                ServerSocket free = new ServerSocket(0)) {
            int modbusPort = free.getLocalPort();
            free.close();
            var simulator = new MultiDeviceRuntime(true, modbusPort, "tcp://127.0.0.1:" + broker.port(), mapper);
            try {
                simulator.start();
                for (int i = 0; i < 30 && received.getCount() > 0; i++) {
                    collector.reconnect();
                    simulator.publish();
                    received.await(200, TimeUnit.MILLISECONDS);
                }
                assertEquals(0, received.getCount());
            } finally {
                simulator.stop();
            }
        } finally {
            collector.stop();
        }
    }
}
