package top.yealike.ipe.simulator.multidevice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MultiDeviceRuntimeTest {
    @Test
    void commandsAreScopedIdempotentAndReflectedInModbus() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        MultiDeviceRuntime runtime = new MultiDeviceRuntime(true, port, "", new ObjectMapper());
        runtime.start();
        try {
            var ack = runtime.command("r1", "SIM-PV-001", "stop");
            assertEquals("standby", ack.get("state"));
            assertEquals(ack, runtime.command("r1", "SIM-PV-001", "stop"));
            assertThrows(IllegalStateException.class, () -> runtime.command("r1", "SIM-PV-001", "start"));
            try (Socket client = new Socket("127.0.0.1", port)) {
                var out = new DataOutputStream(client.getOutputStream());
                var in = new DataInputStream(client.getInputStream());
                out.writeShort(1);
                out.writeShort(0);
                out.writeShort(6);
                out.writeByte(1);
                out.writeByte(3);
                out.writeShort(1);
                out.writeShort(1);
                out.flush();
                assertEquals(1, in.readUnsignedShort());
                assertEquals(0, in.readUnsignedShort());
                assertEquals(5, in.readUnsignedShort());
                assertEquals(1, in.readUnsignedByte());
                assertEquals(3, in.readUnsignedByte());
                assertEquals(2, in.readUnsignedByte());
                assertEquals(0, in.readUnsignedShort());
            }
            runtime.command("r2", "SIM-ESS-001", "charge");
            assertEquals(2, runtime.registers(3)[4]);
            assertTrue((short) runtime.registers(3)[1] < 0);
            runtime.command("r3", "SIM-LOCK-001", "lower");
            assertEquals("lowered", ((java.util.Map<?, ?>) runtime.snapshot().get("lock")).get("state"));
            runtime.scenario("pause");
            assertThrows(IllegalStateException.class, () -> runtime.command("r4", "SIM-GATE-001", "open"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void tokenRequiredForCommands() {
        var runtime = new MultiDeviceRuntime(true, 0, "", new ObjectMapper());
        var controller = new MultiDeviceController(runtime, "secret");
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.command("bad", new MultiDeviceController.Command("x", "SIM-GATE-001", "open")));
        assertEquals(
                "open",
                controller
                        .command("secret", new MultiDeviceController.Command("x", "SIM-GATE-001", "open"))
                        .get("state"));
    }

    @Test
    void mqttPublishesSimulatedDeviceState() throws Exception {
        try (ServerSocket broker = new ServerSocket(0);
                ServerSocket free = new ServerSocket(0)) {
            int modbusPort = free.getLocalPort();
            free.close();
            CountDownLatch published = new CountDownLatch(3);
            Thread reader = new Thread(() -> {
                try (Socket socket = broker.accept()) {
                    socket.setSoTimeout(5000);
                    var in = new DataInputStream(socket.getInputStream());
                    var out = new DataOutputStream(socket.getOutputStream());
                    readPacket(in);
                    out.write(new byte[] {0x20, 2, 0, 0});
                    out.flush();
                    for (int i = 0; i < 3; i++) {
                        byte[] packet = readPacket(in);
                        int topicLength = ((packet[0] & 255) << 8) | (packet[1] & 255);
                        String topic = new String(packet, 2, topicLength, StandardCharsets.UTF_8);
                        if (topic.startsWith("ipe/simulation/SIM-")) published.countDown();
                        int pos = 2 + topicLength;
                        out.write(new byte[] {0x40, 2, packet[pos], packet[pos + 1]});
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            reader.start();
            var runtime = new MultiDeviceRuntime(
                    true, modbusPort, "tcp://127.0.0.1:" + broker.getLocalPort(), new ObjectMapper());
            try {
                runtime.start();
                runtime.publish();
                assertTrue(published.await(5, TimeUnit.SECONDS));
            } finally {
                try {
                    runtime.stop();
                } catch (Exception ignored) {
                }
                reader.join(2000);
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
