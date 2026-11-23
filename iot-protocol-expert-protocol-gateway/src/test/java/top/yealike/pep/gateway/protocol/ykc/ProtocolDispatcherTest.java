package top.yealike.pep.gateway.protocol.ykc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.InboundFrame;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.codec.frames.ParallelFrames;
import top.yealike.pep.codec.frames.RealtimeFrames;
import top.yealike.pep.gateway.device.Connector;
import top.yealike.pep.gateway.device.Device;
import top.yealike.pep.gateway.device.DeviceIdentityRegistry;
import top.yealike.pep.gateway.integration.ops.OpsClient;
import top.yealike.pep.gateway.integration.ops.dto.BillRequest;
import top.yealike.pep.gateway.integration.ops.dto.BillResult;
import top.yealike.pep.gateway.integration.ops.dto.RealtimeRequest;
import top.yealike.pep.gateway.integration.ops.dto.StartResult;
import top.yealike.pep.gateway.protocol.ProtocolType;
import top.yealike.pep.observability.protocol.ProtocolTelemetry;

class ProtocolDispatcherTest {

    private static final String DEVICE_SN = "10000000";
    private static final String EXTERNAL_ID = "32010600000001";
    private static final String ORDER_NO = "202611221000000000001";
    private static final String TRANSACTION_NO = "10000000012026112210000000000001";
    private static final String LOGICAL_CARD = "0000000010000001";
    private static final String ACCOUNT = "0000000000000001";
    private static final int SEQUENCE = 7;

    private SessionRegistry sessions;
    private OpsClient ops;
    private ProtocolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionRegistry.class);
        ops = mock(OpsClient.class);
        DeviceIdentityRegistry identities = mock(DeviceIdentityRegistry.class);
        when(identities.resolvePlatform(ProtocolType.YKC16, DEVICE_SN)).thenReturn(identity());
        dispatcher =
                new ProtocolDispatcher(mock(ProtocolTelemetry.class), sessions, ops, mock(Tracer.class), identities);
    }

    @Test
    void startRequestAckUsesExternalTransactionNo() {
        when(ops.start(any())).thenReturn(new StartResult(true, ORDER_NO, TRANSACTION_NO, 0, "启动通过"));
        OperationFrames.StartRequest request =
                new OperationFrames.StartRequest(EXTERNAL_ID, "01", 1, false, ACCOUNT, "0".repeat(32), "");

        dispatch(FrameType.START_REQUEST, OperationFrames.encodeStartRequest(request));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(sessions).send(eq(DEVICE_SN), eq(FrameType.START_ACK), body.capture(), eq(SEQUENCE));
        OperationFrames.StartAck ack = OperationFrames.decodeStartAck(body.getValue());
        assertEquals(TRANSACTION_NO, ack.transactionNo());
        assertNotEquals(ORDER_NO, ack.transactionNo());
        verify(ops).chargingByTransaction(eq("YKC16"), eq(DEVICE_SN), eq(TRANSACTION_NO), any(LocalDateTime.class));
        verify(ops, never()).charging(eq(ORDER_NO), any());
    }

    @Test
    void remoteStartAckAdvancesOrderByExternalTransactionNo() {
        OperationFrames.RemoteStartAck ack =
                new OperationFrames.RemoteStartAck(TRANSACTION_NO, EXTERNAL_ID, "01", true, 0);

        dispatch(FrameType.REMOTE_START_ACK, OperationFrames.encodeRemoteStartAck(ack));

        verify(ops).chargingByTransaction(eq("YKC16"), eq(DEVICE_SN), eq(TRANSACTION_NO), any(LocalDateTime.class));
        verify(ops, never()).charging(anyString(), any());
    }

    @Test
    void remoteStartRejectionFailsOrderByExternalTransactionNo() {
        OperationFrames.RemoteStartAck ack =
                new OperationFrames.RemoteStartAck(TRANSACTION_NO, EXTERNAL_ID, "01", false, 9);

        dispatch(FrameType.REMOTE_START_ACK, OperationFrames.encodeRemoteStartAck(ack));

        verify(ops).failByTransaction(eq("YKC16"), eq(DEVICE_SN), eq(TRANSACTION_NO), eq("桩拒绝启动: 9"));
        verify(ops, never()).fail(anyString(), anyString());
    }

    @Test
    void parallelStartAckAndProgressUseExternalTransactionNo() {
        when(ops.start(any())).thenReturn(new StartResult(true, ORDER_NO, TRANSACTION_NO, 0, "启动通过"));
        ParallelFrames.ParallelStartRequest request = new ParallelFrames.ParallelStartRequest(
                EXTERNAL_ID, "01", 1, false, ACCOUNT, "0".repeat(32), "", 1, "000001");

        dispatch(FrameType.PARALLEL_START_REQUEST, ParallelFrames.encodeParallelStartRequest(request));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        InOrder inOrder = inOrder(sessions, ops);
        inOrder.verify(sessions).send(eq(DEVICE_SN), eq(FrameType.PARALLEL_START_ACK), body.capture(), eq(SEQUENCE));
        inOrder.verify(ops)
                .chargingByTransaction(eq("YKC16"), eq(DEVICE_SN), eq(TRANSACTION_NO), any(LocalDateTime.class));
        ParallelFrames.ParallelStartAck ack = ParallelFrames.decodeParallelStartAck(body.getValue());
        assertEquals(TRANSACTION_NO, ack.transactionNo());
        assertNotEquals(ORDER_NO, ack.transactionNo());
    }

    @Test
    void parallelStartResultAdvancesOrderByExternalTransactionNo() {
        ParallelFrames.ParallelStartResult result =
                new ParallelFrames.ParallelStartResult(TRANSACTION_NO, EXTERNAL_ID, "01", true, 0, 1, "000001");

        dispatch(FrameType.PARALLEL_START_RESULT, ParallelFrames.encodeParallelStartResult(result));

        verify(ops).chargingByTransaction(eq("YKC16"), eq(DEVICE_SN), eq(TRANSACTION_NO), any(LocalDateTime.class));
        verify(ops, never()).charging(anyString(), any());
    }

    @Test
    void parallelStartResultFailureUsesExternalTransactionNo() {
        ParallelFrames.ParallelStartResult result =
                new ParallelFrames.ParallelStartResult(TRANSACTION_NO, EXTERNAL_ID, "01", false, 9, 1, "000001");

        dispatch(FrameType.PARALLEL_START_RESULT, ParallelFrames.encodeParallelStartResult(result));

        verify(ops).failByTransaction(eq("YKC16"), eq(DEVICE_SN), eq(TRANSACTION_NO), eq("桩拒绝并充启动: 9"));
        verify(ops, never()).fail(anyString(), anyString());
    }

    @Test
    void realtimePassesExternalTransactionNoToOpsStateProgression() {
        RealtimeFrames.Realtime realtime = new RealtimeFrames.Realtime(
                TRANSACTION_NO,
                EXTERNAL_ID,
                "01",
                3,
                1,
                1,
                new BigDecimal("220.0"),
                new BigDecimal("10.0"),
                25,
                "0000000000000000",
                50,
                25,
                1,
                60,
                new BigDecimal("1.0000"),
                new BigDecimal("0.1000"),
                new BigDecimal("1.2000"),
                0,
                null);

        dispatch(FrameType.REALTIME, RealtimeFrames.encodeRealtime(realtime));

        ArgumentCaptor<RealtimeRequest> request = ArgumentCaptor.forClass(RealtimeRequest.class);
        verify(ops).gunState(request.capture());
        assertEquals("YKC16", request.getValue().protocol());
        assertEquals(DEVICE_SN, request.getValue().protocolDeviceId());
        assertEquals(TRANSACTION_NO, request.getValue().transactionNo());
        assertEquals("01", request.getValue().gunNo());
    }

    @Test
    void transactionBillAndAckUseExternalTransactionNo() {
        when(ops.acceptYkc(any())).thenReturn(new BillResult(true, null, 99L, null));
        OperationFrames.EnergySegment segment = new OperationFrames.EnergySegment(
                new BigDecimal("1.00000"),
                new BigDecimal("1.0000"),
                new BigDecimal("0.1000"),
                new BigDecimal("1.1000"));
        OperationFrames.Transaction transaction = new OperationFrames.Transaction(
                TRANSACTION_NO,
                EXTERNAL_ID,
                "01",
                LocalDateTime.parse("2026-11-22T10:00:00"),
                LocalDateTime.parse("2026-11-22T12:00:00"),
                segment,
                segment,
                segment,
                segment,
                new BigDecimal("0.0000"),
                new BigDecimal("4.4000"),
                new BigDecimal("4.0000"),
                new BigDecimal("0.4000"),
                new BigDecimal("4.7500"),
                "",
                1,
                LocalDateTime.parse("2026-11-22T12:00:01"),
                0,
                "0000000000000000");

        dispatch(FrameType.TRANSACTION, OperationFrames.encodeTransaction(transaction));

        ArgumentCaptor<BillRequest> bill = ArgumentCaptor.forClass(BillRequest.class);
        verify(ops).acceptYkc(bill.capture());
        assertEquals(TRANSACTION_NO, bill.getValue().transactionNo());
        assertEquals(new BigDecimal("4.7500"), bill.getValue().totalAmount());
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(sessions).send(eq(DEVICE_SN), eq(FrameType.TRANSACTION_ACK), body.capture(), eq(SEQUENCE));
        OperationFrames.TransactionAck ack = OperationFrames.decodeTransactionAck(body.getValue());
        assertEquals(TRANSACTION_NO, ack.transactionNo());
        assertEquals(true, ack.success());
    }

    private void dispatch(FrameType type, byte[] body) {
        Frame frame = new Frame(SEQUENCE, false, type.code(), body);
        dispatcher.dispatch(DEVICE_SN, EXTERNAL_ID, new InboundFrame(frame, frame.bodyHex()));
    }

    private static Device identity() {
        return new Device(
                ProtocolType.YKC16,
                DEVICE_SN,
                EXTERNAL_ID,
                true,
                Map.of("01", new Connector(DEVICE_SN + "01", "01", "01", 1)));
    }
}
