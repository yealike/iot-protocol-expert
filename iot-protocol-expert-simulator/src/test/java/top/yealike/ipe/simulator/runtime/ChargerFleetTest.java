package top.yealike.ipe.simulator.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;
import top.yealike.ipe.simulator.config.SimulatorProperties;

class ChargerFleetTest {
    @TempDir
    Path temp;

    @Test
    void runsDifferentProtocolsWithIndependentStateAndPreventsDuplicateSelection() {
        SimulatorProperties config = new SimulatorProperties();
        config.setStateFile(temp.resolve("state.properties").toString());
        config.setPlatformPort(1);
        config.setOcppPort(1);
        OpsDeviceClient directory = mock(OpsDeviceClient.class);
        when(directory.get(1L))
                .thenReturn(new OpsDeviceClient.SimulatorDevice(
                        1L, "A", 1L, "S", "YKC16", "32010600000001", 1, false, true, true));
        when(directory.get(2L))
                .thenReturn(new OpsDeviceClient.SimulatorDevice(
                        2L, "B", 1L, "S", "OCPP16J", "OCPP-B", 2, false, true, true));
        SimulatorRuntime interactive = mock(SimulatorRuntime.class);
        when(interactive.state()).thenReturn(Map.of());
        ProtocolTelemetry telemetry = new ProtocolTelemetry(
                io.micrometer.tracing.Tracer.NOOP, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ChargerFleet fleet = new ChargerFleet(config, telemetry, directory, interactive);
        try {
            assertEquals("ykc16", fleet.start(1L).get("protocol"));
            assertEquals("ocpp16j", fleet.start(2L).get("protocol"));
            assertEquals(2, fleet.states().size());
            assertThrows(IllegalArgumentException.class, () -> fleet.selectInteractive(1L));
            fleet.action(1L, "01", "fault", "0x0004");
            @SuppressWarnings("unchecked")
            var first = (Map<String, Object>) fleet.states().stream()
                    .filter(x -> Long.valueOf(1).equals(x.get("deviceId")))
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            var gun = ((java.util.List<Map<String, Object>>) first.get("guns")).getFirst();
            assertEquals(4, gun.get("faultCode"));
            assertEquals(2, fleet.states().size());
            fleet.stop(1L);
            assertEquals(1, fleet.states().size());
        } finally {
            fleet.shutdown();
        }
    }

    @Test
    void ocppFaultCodeSurvivesPlugAndCanBeCleared() {
        SimulatorProperties config = new SimulatorProperties();
        config.setProtocol("ocpp16j");
        config.setAutoConnect(false);
        config.setStateFile(temp.resolve("ocpp.properties").toString());
        var runtime = new SimulatorRuntime(
                config,
                new ProtocolTelemetry(
                        io.micrometer.tracing.Tracer.NOOP,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        runtime.init();
        try {
            runtime.fault("01", true, "GroundFailure");
            runtime.plug("01");
            assertEquals("Faulted", runtime.gunRuntimeStatus("01"));
            assertEquals("GroundFailure", runtime.gunFaultCode("01"));
            runtime.fault("01", false);
            assertEquals("Preparing", runtime.gunRuntimeStatus("01"));
            assertEquals("NoError", runtime.gunFaultCode("01"));
            assertThrows(IllegalArgumentException.class, () -> runtime.fault("01", true, "NotAProtocolCode"));
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    void twoChargersSendIndependentFaultReportsOverTcp() throws Exception {
        var seen = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        var reports = new java.util.concurrent.CountDownLatch(2);
        try (var server = new java.net.ServerSocket(0)) {
            var workers = java.util.concurrent.Executors.newCachedThreadPool();
            var sockets = new java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>();
            workers.submit(() -> {
                try {
                    while (!server.isClosed()) {
                        var socket = server.accept();
                        sockets.add(socket);
                        workers.submit(() -> {
                            try (var input = new java.io.DataInputStream(socket.getInputStream())) {
                                while (!socket.isClosed()) {
                                    int head = input.readUnsignedByte();
                                    int length = input.readUnsignedByte();
                                    byte[] wire = new byte[length + 4];
                                    wire[0] = (byte) head;
                                    wire[1] = (byte) length;
                                    input.readFully(wire, 2, length + 2);
                                    var frame = top.yealike.ipe.protocol.codec.FrameCodec.decode(wire, null);
                                    if (frame.frameType() == top.yealike.ipe.protocol.codec.FrameType.LOGIN.code()) {
                                        var login =
                                                top.yealike.ipe.protocol.codec.frames.RegistrationFrames.decodeLogin(
                                                        frame.body());
                                        var ack = new top.yealike.ipe.protocol.codec.Frame(
                                                frame.sequence(),
                                                false,
                                                top.yealike.ipe.protocol.codec.FrameType.LOGIN_ACK.code(),
                                                top.yealike.ipe.protocol.codec.frames.RegistrationFrames.encodeLoginAck(
                                                        new top.yealike.ipe.protocol.codec.frames.RegistrationFrames
                                                                .LoginAck(login.pileCode(), true)));
                                        socket.getOutputStream()
                                                .write(top.yealike.ipe.protocol.codec.FrameCodec.encode(
                                                        ack, false, null));
                                        socket.getOutputStream().flush();
                                    } else if (frame.frameType()
                                            == top.yealike.ipe.protocol.codec.FrameType.REALTIME.code()) {
                                        var value = top.yealike.ipe.protocol.codec.frames.RealtimeFrames.decodeRealtime(
                                                frame.body());
                                        if (value.hardwareFault() != 0
                                                && seen.putIfAbsent(value.pileCode(), value.hardwareFault()) == null)
                                            reports.countDown();
                                    }
                                }
                            } catch (java.io.IOException ignored) {
                            }
                        });
                    }
                } catch (java.io.IOException ignored) {
                }
            });
            SimulatorProperties config = new SimulatorProperties();
            config.setStateFile(temp.resolve("socket-state.properties").toString());
            config.setPlatformPort(server.getLocalPort());
            OpsDeviceClient directory = mock(OpsDeviceClient.class);
            when(directory.get(11L))
                    .thenReturn(new OpsDeviceClient.SimulatorDevice(
                            11L, "A", 1L, "S", "YKC16", "32010600000011", 1, false, true, true));
            when(directory.get(12L))
                    .thenReturn(new OpsDeviceClient.SimulatorDevice(
                            12L, "B", 1L, "S", "YKC16", "32010600000012", 1, false, true, true));
            SimulatorRuntime interactive = mock(SimulatorRuntime.class);
            when(interactive.state()).thenReturn(Map.of());
            var fleet = new ChargerFleet(
                    config,
                    new ProtocolTelemetry(
                            io.micrometer.tracing.Tracer.NOOP,
                            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                    directory,
                    interactive);
            try {
                fleet.start(11L);
                fleet.start(12L);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (fleet.states().stream()
                                        .filter(x -> Boolean.TRUE.equals(x.get("loggedIn")))
                                        .count()
                                != 2
                        && System.nanoTime() < deadline) Thread.sleep(20);
                assertEquals(
                        2,
                        fleet.states().stream()
                                .filter(x -> Boolean.TRUE.equals(x.get("loggedIn")))
                                .count());
                fleet.action(11L, "01", "fault", "0x0004");
                fleet.action(12L, "01", "fault", "0x0008");
                assertTrue(reports.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(Map.of("32010600000011", 4, "32010600000012", 8), seen);
            } finally {
                fleet.shutdown();
                server.close();
                for (var socket : sockets) socket.close();
                workers.shutdownNow();
            }
        }
    }
}
