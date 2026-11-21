package top.yealike.pep.gateway.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.codec.ocpp16.OcppMessageCodec;
import top.yealike.pep.gateway.config.ProtocolProperties;
import top.yealike.pep.gateway.core.DeviceIdentityRegistry;
import top.yealike.pep.gateway.core.ProtocolType;
import top.yealike.pep.gateway.netty.SessionRegistry;
import top.yealike.pep.gateway.ocpp16.OcppCommandFacade;
import top.yealike.pep.gateway.ocpp16.OcppSessionRegistry;
import top.yealike.pep.gateway.ops.OpsDtos;

class ChannelCommandServiceTest {
    @Test
    void remoteStartDoesNotFallBackToPlatformOrderNo() {
        var sessions = mock(SessionRegistry.class);
        var service = new ChannelCommandService(
                sessions,
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                mock(OcppCommandFacade.class),
                new ProtocolProperties(),
                identityRegistry(ProtocolType.YKC16));

        var result = service.execute(new OpsDtos.CommandRequest(
                "YKC16",
                "10000000",
                "remote-start",
                Map.of("orderNo", "202611221000000000001", "gunNo", "01"),
                "test"));

        assertFalse(result.success());
        assertTrue(result.message().contains("transactionNo"));
        verifyNoInteractions(sessions);
    }

    @Test
    void remoteStartEncodesLogicalAndPhysicalCardsSeparately() {
        var sessions = mock(SessionRegistry.class);
        var service = new ChannelCommandService(
                sessions,
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                mock(OcppCommandFacade.class),
                new ProtocolProperties(),
                identityRegistry(ProtocolType.YKC16));
        String pile = "10000000";
        String orderNo = "202611221000000000001";
        String transactionNo = "10000000012026112210000000000001";
        var ack = new OperationFrames.RemoteStartAck(transactionNo, pile, "01", true, 0);
        when(sessions.request(
                        anyString(),
                        eq(FrameType.REMOTE_START),
                        eq(FrameType.REMOTE_START_ACK),
                        any(byte[].class),
                        any()))
                .thenReturn(new Frame(
                        1, false, FrameType.REMOTE_START_ACK.code(), OperationFrames.encodeRemoteStartAck(ack)));

        var result = service.execute(new OpsDtos.CommandRequest(
                "YKC16",
                pile,
                "remote-start",
                Map.of(
                        "orderNo",
                        orderNo,
                        "transactionNo",
                        transactionNo,
                        "gunNo",
                        "01",
                        "idTag",
                        "10000001",
                        "physicalCard",
                        "00000000D14B0A54"),
                "test"));

        assertTrue(result.success(), result.message());
        var body = ArgumentCaptor.forClass(byte[].class);
        verify(sessions)
                .request(eq(pile), eq(FrameType.REMOTE_START), eq(FrameType.REMOTE_START_ACK), body.capture(), any());
        var command = OperationFrames.decodeRemoteStart(body.getValue());
        assertEquals(transactionNo, command.transactionNo());
        assertNotEquals(orderNo, command.transactionNo());
        assertEquals("0000000010000001", command.logicalCard());
        assertEquals("00000000D14B0A54", command.physicalCard());
    }

    @Test
    void usesConfiguredPendingTimeoutWithoutAnImplicitFloor() {
        var sessions = mock(SessionRegistry.class);
        ProtocolProperties properties = new ProtocolProperties();
        properties.setPendingTimeoutSeconds(3);
        var service = new ChannelCommandService(
                sessions,
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                mock(OcppCommandFacade.class),
                properties,
                identityRegistry(ProtocolType.YKC16));
        String pile = "10000000";
        String transactionNo = "10000000012026112210000000000001";
        var ack = new OperationFrames.RemoteStartAck(transactionNo, pile, "01", true, 0);
        when(sessions.request(
                        anyString(),
                        eq(FrameType.REMOTE_START),
                        eq(FrameType.REMOTE_START_ACK),
                        any(byte[].class),
                        any()))
                .thenReturn(new Frame(
                        1, false, FrameType.REMOTE_START_ACK.code(), OperationFrames.encodeRemoteStartAck(ack)));

        service.execute(new OpsDtos.CommandRequest(
                "YKC16", pile, "remote-start", Map.of("transactionNo", transactionNo, "gunNo", "01"), "test"));

        verify(sessions)
                .request(
                        eq(pile),
                        eq(FrameType.REMOTE_START),
                        eq(FrameType.REMOTE_START_ACK),
                        any(byte[].class),
                        eq(Duration.ofSeconds(3)));
    }

    @Test
    void rejectsOcppCommandWhenDeviceReturnsRejected() {
        var sessions = mock(OcppSessionRegistry.class);
        var ocpp = mock(OcppCommandFacade.class);
        var service = new ChannelCommandService(
                mock(SessionRegistry.class),
                sessions,
                mock(CommandFacade.class),
                ocpp,
                new ProtocolProperties(),
                identityRegistry(ProtocolType.OCPP16J));
        when(ocpp.changeConfiguration("10000001", "HeartbeatInterval", "10"))
                .thenReturn(OcppMessageCodec.object().put("status", "Rejected"));

        var result = service.execute(new OpsDtos.CommandRequest(
                "OCPP16J",
                "10000001",
                "configuration",
                Map.of("method", "SET", "key", "HeartbeatInterval", "value", "10"),
                "test"));

        assertFalse(result.success());
        assertTrue(result.message().contains("Rejected"));
    }

    @Test
    void rejectsOcppCommandWhenDeviceReturnsNotSupported() {
        var sessions = mock(OcppSessionRegistry.class);
        var ocpp = mock(OcppCommandFacade.class);
        var service = new ChannelCommandService(
                mock(SessionRegistry.class),
                sessions,
                mock(CommandFacade.class),
                ocpp,
                new ProtocolProperties(),
                identityRegistry(ProtocolType.OCPP16J));
        when(ocpp.unlockConnector("10000001", 1))
                .thenReturn(OcppMessageCodec.object().put("status", "NotSupported"));

        var result = service.execute(
                new OpsDtos.CommandRequest("OCPP16J", "10000001", "unlock", Map.of("connectorId", 1), "test"));

        assertFalse(result.success());
        assertTrue(result.message().contains("NotSupported"));
    }

    @Test
    void acceptsOcppUnlockedAsSuccessfulCommandResult() {
        var sessions = mock(OcppSessionRegistry.class);
        var ocpp = mock(OcppCommandFacade.class);
        var service = new ChannelCommandService(
                mock(SessionRegistry.class),
                sessions,
                mock(CommandFacade.class),
                ocpp,
                new ProtocolProperties(),
                identityRegistry(ProtocolType.OCPP16J));
        when(ocpp.unlockConnector("10000001", 1))
                .thenReturn(OcppMessageCodec.object().put("status", "Unlocked"));

        var result = service.execute(
                new OpsDtos.CommandRequest("OCPP16J", "10000001", "unlock", Map.of("connectorId", 1), "test"));

        assertTrue(result.success(), result.message());
    }

    @Test
    void timeoutIsStructuredSoOrderCanStayPending() {
        var sessions = mock(SessionRegistry.class);
        var service = new ChannelCommandService(
                sessions,
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                mock(OcppCommandFacade.class),
                new ProtocolProperties(),
                identityRegistry(ProtocolType.YKC16));
        doThrow(new SessionRegistry.CommandTimeoutException("等待应答超时"))
                .when(sessions)
                .request(anyString(), any(), any(), any(), any());

        var result = service.execute(new OpsDtos.CommandRequest(
                "YKC16",
                "32010600000001",
                "remote-start",
                Map.of("transactionNo", "10000000012026112210000000000001", "gunNo", "01"),
                "test"));

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.data().get("outcome"), result.message());
    }

    @Test
    void transportFailureIsUnknownInsteadOfRejected() {
        var sessions = mock(SessionRegistry.class);
        var service = new ChannelCommandService(
                sessions,
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                mock(OcppCommandFacade.class),
                new ProtocolProperties(),
                identityRegistry(ProtocolType.YKC16));
        doThrow(new IllegalStateException("设备连接断开")).when(sessions).request(anyString(), any(), any(), any(), any());

        var result = service.execute(new OpsDtos.CommandRequest(
                "YKC16",
                "32010600000001",
                "remote-start",
                Map.of("transactionNo", "10000000012026112210000000000001", "gunNo", "01"),
                "test"));

        assertFalse(result.success());
        assertEquals("UNKNOWN", result.data().get("outcome"));
    }

    @Test
    void ocppTimeoutIsReportedAsTimeoutInsteadOfUnknown() {
        var ocppCommands = mock(OcppCommandFacade.class);
        when(ocppCommands.rawCall(anyString(), anyString(), any()))
                .thenThrow(new OcppSessionRegistry.OcppCommandException(
                        "等待 RemoteStartTransaction 应答超时", new TimeoutException("timeout")));
        var service = new ChannelCommandService(
                mock(SessionRegistry.class),
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                ocppCommands,
                new ProtocolProperties(),
                identityRegistry(ProtocolType.OCPP16J));

        var result = service.execute(new OpsDtos.CommandRequest(
                "OCPP16J",
                "10000001",
                "remote-start",
                Map.of("orderNo", "12345678901234567890123456789012", "gunNo", "01"),
                "test"));

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.data().get("outcome"));
    }

    private static DeviceIdentityRegistry identityRegistry(ProtocolType protocol) {
        DeviceIdentityRegistry registry = mock(DeviceIdentityRegistry.class);
        String deviceSn = protocol == ProtocolType.YKC16 ? "10000000" : "10000001";
        String externalId = protocol == ProtocolType.YKC16 ? "32010600000001" : "CP-001";
        DeviceIdentityRegistry.Device device = new DeviceIdentityRegistry.Device(
                protocol,
                deviceSn,
                externalId,
                true,
                Map.of("01", new DeviceIdentityRegistry.Connector(deviceSn + "01", "01", "01", 1)));
        when(registry.resolve(eq(protocol), anyString())).thenReturn(device);
        return registry;
    }
}
