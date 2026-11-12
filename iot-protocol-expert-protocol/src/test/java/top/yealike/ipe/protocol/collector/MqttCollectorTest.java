package top.yealike.ipe.protocol.collector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.ops.OpsClient;

class MqttCollectorTest {
    @TempDir
    Path dir;

    @Test
    void mqttMessageIsMappedAndForwarded() throws Exception {
        var sample = new ObjectMapper()
                .readValue(
                        Path.of("src/main/resources/collector-examples/simulator-mqtt.json")
                                .toFile(),
                        MqttCollector.Config.class);
        assertEquals(3, sample.devices().size());
        try (ServerSocket broker = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket s = broker.accept()) {
                    s.setSoTimeout(5000);
                    var in = new DataInputStream(s.getInputStream());
                    var out = new DataOutputStream(s.getOutputStream());
                    readPacket(in);
                    out.write(new byte[] {0x20, 2, 0, 0});
                    out.flush();
                    byte[] subscribe = readPacket(in);
                    out.write(new byte[] {(byte) 0x90, 3, subscribe[0], subscribe[1], 1});
                    out.flush();
                    byte[] topic = "ipe/simulation/SIM-LOCK-001/properties".getBytes(StandardCharsets.UTF_8);
                    byte[] payload = "{\"state\":\"lowered\",\"batteryPercent\":85}".getBytes(StandardCharsets.UTF_8);
                    out.writeByte(0x30);
                    out.writeByte(2 + topic.length + payload.length);
                    out.writeShort(topic.length);
                    out.write(topic);
                    out.write(payload);
                    out.flush();
                    Thread.sleep(500);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            responder.start();
            String conf = "{\"broker\":{\"host\":\"127.0.0.1\",\"port\":" + broker.getLocalPort()
                    + ",\"tls\":false,\"clientId\":\"test-collector\"},\"devices\":[{\"topic\":\"ipe/simulation/SIM-LOCK-001/properties\",\"protocolDeviceId\":\"SIM-LOCK-001\",\"properties\":{\"state\":\"state\",\"batteryPercent\":\"batteryPercent\"}}]}";
            Path file = dir.resolve("mqtt.json");
            Files.writeString(file, conf);
            OpsClient ops = mock(OpsClient.class);
            CountDownLatch received = new CountDownLatch(1);
            doAnswer(inv -> {
                        received.countDown();
                        return 2;
                    })
                    .when(ops)
                    .properties(eq("MQTT"), eq("SIM-LOCK-001"), eq(""), anyMap());
            var collector = new MqttCollector(ops, new ObjectMapper(), file.toString(), "", "");
            try {
                collector.start();
                assertTrue(received.await(5, TimeUnit.SECONDS));
                verify(ops).properties("MQTT", "SIM-LOCK-001", "", Map.of("state", "lowered", "batteryPercent", 85));
            } finally {
                collector.stop();
                responder.join(2000);
            }
        }
    }

    private static byte[] readPacket(DataInputStream in) throws Exception {
        in.readUnsignedByte();
        int length = 0, multiplier = 1, digit;
        do {
            digit = in.readUnsignedByte();
            length += (digit & 127) * multiplier;
            multiplier *= 128;
        } while ((digit & 128) != 0);
        return in.readNBytes(length);
    }
}
