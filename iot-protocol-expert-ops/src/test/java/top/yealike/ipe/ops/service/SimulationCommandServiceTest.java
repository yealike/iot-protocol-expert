package top.yealike.ipe.ops.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.domain.CommandRecord;
import top.yealike.ipe.ops.repository.CommandRecordRepository;
import top.yealike.ipe.ops.thingmodel.ThingModelCodec;

class SimulationCommandServiceTest {
    private HttpServer server;
    private DeviceService devices;
    private CommandRecordRepository records;
    private AuditService audits;
    private SimulationCommandService commands;
    private DeviceInstance device;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/command", exchange -> {
            assertEquals("test-secret", exchange.getRequestHeaders().getFirst("X-Simulation-Token"));
            var mapper = ThingModelCodec.mapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> data =
                    mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            byte[] response = mapper.writeValueAsBytes(Map.of(
                    "requestId",
                    data.get("requestId"),
                    "device",
                    data.get("device"),
                    "action",
                    data.get("action"),
                    "success",
                    true,
                    "state",
                    "lowered"));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        devices = mock(DeviceService.class);
        records = mock(CommandRecordRepository.class);
        audits = mock(AuditService.class);
        when(records.save(any(CommandRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        device = new DeviceInstance();
        device.id = 7L;
        device.deviceType = "LOCK";
        device.protocol = "MQTT";
        device.protocolDeviceId = "SIM-LOCK-001";
        device.enabled = true;
        device.allowSimulate = true;
        device.allowWork = true;
        when(devices.get(7L)).thenReturn(device);
        commands = new SimulationCommandService(
                devices,
                records,
                audits,
                ThingModelCodec.mapper(),
                "test-secret",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/command");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsOnlyPermittedSimulatedCommandAndRecordsResult() {
        var result = commands.submit(7L, "lower", "operator");
        assertEquals(CommandRecord.Status.SUCCESS, result.status);
        assertNotNull(result.sentAt);
        assertNotNull(result.ackAt);
        verify(records, times(2)).save(any(CommandRecord.class));
        verify(audits)
                .record(eq("operator"), eq("simulation.lower"), eq("device"), eq("7"), eq("SUCCESS"), anyString());
    }

    @Test
    void rejectsNonSimulationAndDisallowedOperations() {
        device.protocolDeviceId = "REAL-LOCK";
        assertThrows(IllegalArgumentException.class, () -> commands.submit(7L, "lower", "operator"));
        device.protocolDeviceId = "SIM-LOCK-001";
        device.allowWork = false;
        assertThrows(IllegalArgumentException.class, () -> commands.submit(7L, "lower", "operator"));
        device.allowWork = true;
        assertThrows(IllegalArgumentException.class, () -> commands.submit(7L, "open", "operator"));
        device.protocol = "MODBUS_TCP";
        assertThrows(IllegalArgumentException.class, () -> commands.submit(7L, "lower", "operator"));
        verifyNoInteractions(records);
    }

    @Test
    void refusesNonLoopbackDestination() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationCommandService(
                        devices, records, audits, ThingModelCodec.mapper(), "x", "http://example.com:19081/command"));
    }
}
