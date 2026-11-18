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
import top.yealike.pep.gateway.netty.SessionRegistry;
import top.yealike.pep.gateway.ocpp16.OcppCommandFacade;
import top.yealike.pep.gateway.ocpp16.OcppSessionRegistry;
import top.yealike.pep.gateway.ops.OpsDtos;

class ChannelCommandServiceTest {
    @Test
    void remoteStartEncodesLogicalAndPhysicalCardsSeparately() {
        var sessions = mock(SessionRegistry.class);
        var service = new ChannelCommandService(
                sessions,
                mock(OcppSessionRegistry.class),
                mock(CommandFacade.class),
                mock(OcppCommandFacade.class),
                new ProtocolProperties());
        String pile = "32010600000001";
        String order = "12345678901234567890123456789012";
        var ack = new OperationFrames.RemoteStartAck(order, pile, "01", true, 0);
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
                Map.of("orderNo", order, "gunNo", "01", "idTag", "10000001", "physicalCard", "00000000D14B0A54"),
                "test"));

        assertTrue(result.success(), result.message());
        var body = ArgumentCaptor.forClass(byte[].class);
        verify(sessions)
                .request(eq(pile), eq(FrameType.REMOTE_START), eq(FrameType.REMOTE_START_ACK), body.capture(), any());
        var command = OperationFrames.decodeRemoteStart(body.getValue());
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
                properties);
        String pile = "32010600000001";
        String order = "12345678901234567890123456789012";
        var ack = new OperationFrames.RemoteStartAck(order, pile, "01", true, 0);
        when(sessions.request(
                        anyString(),
                        eq(FrameType.REMOTE_START),
                        eq(FrameType.REMOTE_START_ACK),
                        any(byte[].class),
                        any()))
                .thenReturn(new Frame(
                        1, false, FrameType.REMOTE_START_ACK.code(), OperationFrames.encodeRemoteStartAck(ack)));

        service.execute(new OpsDtos.CommandRequest(
                "YKC16", pile, "remote-start", Map.of("orderNo", order, "gunNo", "01"), "test"));

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
                mock(SessionRegistry.class), sessions, mock(CommandFacade.class), ocpp, new ProtocolProperties());
        when(ocpp.changeConfiguration("pile", "HeartbeatInterval", "10"))
                .thenReturn(OcppMessageCodec.object().put("status", "Rejected"));

        var result = service.execute(new OpsDtos.CommandRequest(
                "OCPP16J",
                "pile",
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
                mock(SessionRegistry.class), sessions, mock(CommandFacade.class), ocpp, new ProtocolProperties());
        when(ocpp.unlockConnector("pile", 1))
                .thenReturn(OcppMessageCodec.object().put("status", "NotSupported"));

        var result = service.execute(
                new OpsDtos.CommandRequest("OCPP16J", "pile", "unlock", Map.of("connectorId", 1), "test"));

        assertFalse(result.success());
        assertTrue(result.message().contains("NotSupported"));
    }

    @Test
    void acceptsOcppUnlockedAsSuccessfulCommandResult() {
        var sessions = mock(OcppSessionRegistry.class);
        var ocpp = mock(OcppCommandFacade.class);
        var service = new ChannelCommandService(
                mock(SessionRegistry.class), sessions, mock(CommandFacade.class), ocpp, new ProtocolProperties());
        when(ocpp.unlockConnector("pile", 1))
                .thenReturn(OcppMessageCodec.object().put("status", "Unlocked"));

        var result = service.execute(
                new OpsDtos.CommandRequest("OCPP16J", "pile", "unlock", Map.of("connectorId", 1), "test"));

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
                new ProtocolProperties());
        doThrow(new SessionRegistry.CommandTimeoutException("等待应答超时"))
                .when(sessions)
                .request(anyString(), any(), any(), any(), any());

        var result = service.execute(new OpsDtos.CommandRequest(
                "YKC16",
                "32010600000001",
                "remote-start",
                Map.of("orderNo", "12345678901234567890123456789012", "gunNo", "01"),
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
                new ProtocolProperties());
        doThrow(new IllegalStateException("设备连接断开")).when(sessions).request(anyString(), any(), any(), any(), any());

        var result = service.execute(new OpsDtos.CommandRequest(
                "YKC16",
                "32010600000001",
                "remote-start",
                Map.of("orderNo", "12345678901234567890123456789012", "gunNo", "01"),
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
                new ProtocolProperties());

        var result = service.execute(new OpsDtos.CommandRequest(
                "OCPP16J",
                "pile",
                "remote-start",
                Map.of("orderNo", "12345678901234567890123456789012", "gunNo", "01"),
                "test"));

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.data().get("outcome"));
    }
}
