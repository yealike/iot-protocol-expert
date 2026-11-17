package top.yealike.pep.gateway.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.gateway.netty.SessionRegistry;
import top.yealike.pep.gateway.ocpp16.OcppCommandFacade;
import top.yealike.pep.gateway.ocpp16.OcppSessionRegistry;
import top.yealike.pep.gateway.ops.OpsDtos;

class ChannelCommandServiceTest {
    @Test
    void remoteStartEncodesLogicalAndPhysicalCardsSeparately() {
        var sessions = mock(SessionRegistry.class);
        var service = new ChannelCommandService(
                sessions, mock(OcppSessionRegistry.class), mock(CommandFacade.class), mock(OcppCommandFacade.class));
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
}
