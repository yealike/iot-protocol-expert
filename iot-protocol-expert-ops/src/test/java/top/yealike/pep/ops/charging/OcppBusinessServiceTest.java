package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.service.BillService;
import top.yealike.pep.ops.charging.service.OcppBusinessService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.charging.service.RateModelService;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.service.DevicePropertyService;
import top.yealike.pep.ops.devices.service.DeviceService;
import top.yealike.pep.ops.telemetry.OrderTelemetryService;

class OcppBusinessServiceTest {

    @Test
    void availableDoesNotMarkConnectorAsPlugged() {
        Fixture fixture = fixture();
        fixture.service.status(new OpsDtos.StatusRequest("pile", 1, "Available", "NoError"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(fixture.properties).writeBatch(eq(1L), eq("01"), values.capture(), isNull());
        assertEquals("idle", values.getValue().get("gunState"));
        assertEquals(false, values.getValue().get("plugged"));

        fixture.service.status(new OpsDtos.StatusRequest("pile", 1, "Preparing", "NoError"));
        verify(fixture.properties, times(2)).writeBatch(eq(1L), eq("01"), values.capture(), isNull());
        assertEquals("preparing", values.getValue().get("gunState"));
        assertEquals(true, values.getValue().get("plugged"));
    }

    @Test
    void faultPreservesKnownPluggedState() {
        Fixture fixture = fixture();
        when(fixture.properties.boolOf(1L, "01", "plugged", false)).thenReturn(true);

        fixture.service.status(new OpsDtos.StatusRequest("pile", 1, "Faulted", "OtherError"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(fixture.properties).writeBatch(eq(1L), eq("01"), values.capture(), isNull());
        assertEquals("fault", values.getValue().get("gunState"));
        assertEquals(true, values.getValue().get("plugged"));
    }

    @Test
    void startTransactionRejectsDifferentIdTagFromPendingRemoteStart() {
        Fixture fixture = fixture();
        ChargeOrder order = chargingOrder();
        order.status = ChargeOrder.Status.PENDING;
        order.ocppTransactionId = null;
        order.logicalCard = "TAG-A";
        order.physicalCard = "CARD-A";
        when(fixture.orders.active(1L, "01")).thenReturn(order);
        when(fixture.orders.lockByOrderNo("ORDER-1")).thenReturn(order);

        OpsDtos.StartTransactionResult result = fixture.service.startTransaction(new OpsDtos.StartTransactionRequest(
                "pile", 1, "TAG-B", BigDecimal.ZERO, LocalDateTime.parse("2026-11-18T12:00:00")));

        assertFalse(result.accepted());
        assertTrue(result.message().contains("idTag"));
        verify(fixture.orders, never()).bindOcppTransactionId(any(), any());
        verify(fixture.orders, never()).markCharging(any(), any());
    }

    @Test
    void stopTransactionRejectsUnknownTransaction() {
        Fixture fixture = fixture();
        when(fixture.orders.lockOcppTransactionId(1L, 99)).thenReturn(null);

        OpsDtos.StopTransactionResult result =
                fixture.service.stopTransaction(stopRequest("pile", 99, new BigDecimal("1200")));

        assertFalse(result.accepted());
        assertTrue(result.message().contains("不存在"));
        verify(fixture.orders, never()).settle(any(), any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(fixture.bills);
    }

    @Test
    void stopTransactionRejectsOrderOwnedByAnotherDevice() {
        Fixture fixture = fixture();
        ChargeOrder order = chargingOrder();
        order.deviceId = 2L;
        when(fixture.orders.lockOcppTransactionId(1L, 3)).thenReturn(order);

        OpsDtos.StopTransactionResult result =
                fixture.service.stopTransaction(stopRequest("pile", 3, new BigDecimal("1200")));

        assertFalse(result.accepted());
        assertTrue(result.message().contains("不属于当前设备"));
        verify(fixture.orders, never()).settle(any(), any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(fixture.bills);
    }

    @Test
    void stopTransactionRejectsOrderThatHasNotStarted() {
        Fixture fixture = fixture();
        ChargeOrder order = chargingOrder();
        order.status = ChargeOrder.Status.PENDING;
        when(fixture.orders.lockOcppTransactionId(1L, 3)).thenReturn(order);

        OpsDtos.StopTransactionResult result =
                fixture.service.stopTransaction(stopRequest("pile", 3, new BigDecimal("1200")));

        assertFalse(result.accepted());
        assertTrue(result.message().contains("状态不允许"));
        verify(fixture.orders, never()).settle(any(), any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(fixture.bills);
    }

    @Test
    void duplicateStopTransactionIsIdempotentlyAcceptedWithoutNewSettlement() {
        Fixture fixture = fixture();
        ChargeOrder order = chargingOrder();
        order.status = ChargeOrder.Status.SETTLED;
        order.energy = new BigDecimal("12.3456");
        order.amount = new BigDecimal("8.8888");
        when(fixture.orders.lockOcppTransactionId(1L, 3)).thenReturn(order);

        OpsDtos.StopTransactionResult result =
                fixture.service.stopTransaction(stopRequest("pile", 3, new BigDecimal("13000")));

        assertTrue(result.accepted());
        assertEquals(new BigDecimal("12.3456"), result.energy());
        assertEquals(new BigDecimal("8.8888"), result.amount());
        verify(fixture.orders, never()).settle(any(), any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(fixture.bills);
    }

    private static OpsDtos.StopTransactionRequest stopRequest(
            String pileCode, int transactionId, BigDecimal meterStop) {
        return new OpsDtos.StopTransactionRequest(
                pileCode, transactionId, meterStop, LocalDateTime.parse("2026-11-18T12:00:00"), "Remote");
    }

    private static ChargeOrder chargingOrder() {
        ChargeOrder order = new ChargeOrder();
        order.orderNo = "ORDER-1";
        order.deviceId = 1L;
        order.gunNo = "01";
        order.protocol = "OCPP16J";
        order.ocppTransactionId = 3;
        order.meterStartWh = BigDecimal.ZERO;
        order.status = ChargeOrder.Status.CHARGING;
        order.startTime = LocalDateTime.parse("2026-11-18T11:00:00");
        order.energy = BigDecimal.ZERO;
        order.amount = BigDecimal.ZERO;
        return order;
    }

    private static Fixture fixture() {
        var devices = mock(DeviceService.class);
        var orders = mock(OrderService.class);
        var properties = mock(DevicePropertyService.class);
        var bills = mock(BillService.class);
        var rates = mock(RateModelService.class);
        DeviceInstance device = new DeviceInstance();
        device.id = 1L;
        device.deviceCode = "pile";
        device.protocol = "OCPP16J";
        when(devices.resolve("OCPP16J", "pile")).thenReturn(device);
        when(devices.gunCount(1L)).thenReturn(1);
        var service =
                new OcppBusinessService(mock(OrderTelemetryService.class), devices, orders, properties, bills, rates);
        return new Fixture(service, orders, properties, bills);
    }

    private record Fixture(
            OcppBusinessService service, OrderService orders, DevicePropertyService properties, BillService bills) {}
}
