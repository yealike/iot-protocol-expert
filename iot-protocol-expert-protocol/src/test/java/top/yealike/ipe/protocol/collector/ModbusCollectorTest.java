package top.yealike.ipe.protocol.collector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.ops.OpsClient;

class ModbusCollectorTest {
    @Test
    void jsonExampleAndRealSocketRead() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var config = mapper.readValue(
                Path.of("src/main/resources/collector-examples/simulator-modbus.json")
                        .toFile(),
                ModbusCollector.Config.class);
        assertEquals("running", config.devices().get(0).points().get(3).enums().get("1"));
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket s = server.accept()) {
                    var in = new DataInputStream(s.getInputStream());
                    var out = new DataOutputStream(s.getOutputStream());
                    int tx = in.readUnsignedShort();
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    int unit = in.readUnsignedByte();
                    int function = in.readUnsignedByte();
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    out.writeShort(tx);
                    out.writeShort(0);
                    out.writeShort(5);
                    out.writeByte(unit);
                    out.writeByte(function);
                    out.writeByte(2);
                    out.writeShort(65526);
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            responder.start();
            var ops = mock(OpsClient.class);
            var collector = new ModbusCollector(ops, mapper, "");
            var point = new ModbusCollector.Point(0, 3, "int16", "activePower", "", null, .1, null, null, null);
            collector.poll(new ModbusCollector.Device(
                    "127.0.0.1", server.getLocalPort(), 1, "SIM-TEST", "MODBUS_TCP", 5, List.of(point)));
            verify(ops).properties("MODBUS_TCP", "SIM-TEST", "", Map.of("activePower", -1d));
            responder.join(2000);
        }
    }

    @Test
    void rejectsDuplicatePoints() {
        var p = new ModbusCollector.Point(0, 3, "uint16", "soc", "", null, .1, null, null, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModbusCollector.validate(
                        new ModbusCollector.Device("localhost", 502, 1, "x", null, 5, List.of(p, p))));
    }
}
