package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.audit.AuditService;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.charging.service.CommandService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.config.OpsProperties;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;
import top.yealike.pep.ops.devices.service.ProtocolClient;

class CommandServiceOcppStateTest {

    @Test
    void acceptedOcppRemoteStartDoesNotPretendStartTransactionWasConfirmed() {
        Fixture fixture = fixture();
        ChargeOrder order = fixture.order;
        when(fixture.orders.create(eq(1L), eq("01"), eq(1), eq("card"), eq("tag"), eq("OCPP16J"), eq(true)))
                .thenReturn(order);
        when(fixture.protocol.execute(any(), any()))
                .thenReturn(new OpsDtos.CommandResult(true, "OCPP 远程启动已受理", Map.of("status", "Accepted")));

        Map<String, Object> result = fixture.service.remoteStart(1L, "01", "tag", "card", "100", "test");

        assertEquals("SUCCESS", result.get("status"));
        verify(fixture.orders, never()).markCharging(any(), any());
        verify(fixture.orders, never()).fail(any(), any());
    }

    @Test
    void acceptedOcppRemoteStopWaitsForStopTransactionSettlement() {
        Fixture fixture = fixture();
        ChargeOrder order = fixture.order;
        order.status = ChargeOrder.Status.CHARGING;
        order.ocppTransactionId = 7;
        when(fixture.orders.active(1L, "01")).thenReturn(order);
        when(fixture.protocol.execute(any(), any()))
                .thenReturn(new OpsDtos.CommandResult(true, "OCPP 远程停机已受理", Map.of("status", "Accepted")));

        Map<String, Object> result = fixture.service.remoteStop(1L, "01", "test");

        assertEquals("SUCCESS", result.get("status"));
        verify(fixture.orders, never()).stop(any(), anyInt());
    }

    @Test
    void unknownRemoteStartKeepsOrderPendingForDeviceConfirmation() {
        Fixture fixture = fixture();
        when(fixture.orders.create(any(), any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(fixture.order);
        when(fixture.protocol.execute(any(), any())).thenThrow(new IllegalStateException("协议通道读取超时"));

        Map<String, Object> result = fixture.service.remoteStart(1L, "01", "tag", "card", "100", "test");

        assertEquals("UNKNOWN", result.get("status"));
        assertEquals(false, result.get("success"));
        verify(fixture.orders, never()).fail(any(), any());
    }

    @Test
    void blankIdTagUsesTheSameValueForOrderAndDeviceCommand() {
        Fixture fixture = fixture();
        when(fixture.orders.create(eq(1L), eq("01"), eq(1), eq("card"), eq("IPE-APP"), eq("OCPP16J"), eq(true)))
                .thenReturn(fixture.order);
        when(fixture.protocol.execute(any(), any()))
                .thenReturn(new OpsDtos.CommandResult(
                        true, "OCPP 远程启动已受理", Map.of("status", "Accepted", "outcome", "ACCEPTED")));

        fixture.service.remoteStart(1L, "01", null, "card", "100", "test");

        verify(fixture.orders).create(eq(1L), eq("01"), eq(1), eq("card"), eq("IPE-APP"), eq("OCPP16J"), eq(true));
    }

    @Test
    void explicitDeviceRejectionFailsPendingOrder() {
        Fixture fixture = fixture();
        when(fixture.orders.create(any(), any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(fixture.order);
        when(fixture.protocol.execute(any(), any()))
                .thenReturn(new OpsDtos.CommandResult(
                        false, "设备拒绝远程启动: Rejected", Map.of("status", "Rejected", "outcome", "REJECTED")));

        Map<String, Object> result = fixture.service.remoteStart(1L, "01", "tag", "card", "100", "test");

        assertEquals("REJECTED", result.get("status"));
        verify(fixture.orders).fail(eq("ORDER-1"), any());
    }

    private static Fixture fixture() {
        var commands = mock(CommandRecordMapper.class);
        var devices = mock(DeviceService.class);
        var orders = mock(OrderService.class);
        var protocol = mock(ProtocolClient.class);
        var audits = mock(AuditService.class);
        when(commands.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DeviceInstance device = new DeviceInstance();
        device.id = 1L;
        device.deviceCode = "pile";
        device.protocol = "OCPP16J";
        device.protocolDeviceId = "pile";
        device.state = DeviceInstance.STATE_ONLINE;
        when(devices.get(1L)).thenReturn(device);
        when(devices.gunCount(1L)).thenReturn(1);
        ChargeOrder order = new ChargeOrder();
        order.orderNo = "ORDER-1";
        order.deviceId = 1L;
        order.gunNo = "01";
        order.protocol = "OCPP16J";
        order.status = ChargeOrder.Status.PENDING;
        var service = new CommandService(
                commands, devices, orders, protocol, new OpsProperties(), audits, mock(ObjectMapper.class));
        return new Fixture(service, orders, protocol, order);
    }

    private record Fixture(CommandService service, OrderService orders, ProtocolClient protocol, ChargeOrder order) {}
}
