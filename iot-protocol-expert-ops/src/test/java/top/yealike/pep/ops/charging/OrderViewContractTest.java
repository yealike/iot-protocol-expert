package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.yealike.pep.ops.api.admin.OrderController;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.ChargeOrderSegment;
import top.yealike.pep.ops.charging.domain.OrderExternalReference;
import top.yealike.pep.ops.charging.dto.OrderView;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.OrderExternalReferenceMapper;
import top.yealike.pep.ops.charging.service.BillService;
import top.yealike.pep.ops.charging.service.ChargeBillingService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;
import top.yealike.pep.ops.telemetry.service.OrderTelemetryService;

class OrderViewContractTest {

    @Test
    void platformOrderNoIsAlwaysTwentyOneDigits() {
        OrderService service = service(
                mock(ChargeOrderMapper.class), mock(OrderExternalReferenceMapper.class), mock(DeviceService.class));

        assertTrue(service.nextOrderNo().matches("[0-9]{21}"));
    }

    @Test
    void ykcTransactionNoIsAlwaysThirtyTwoDigits() {
        OrderService service = service(
                mock(ChargeOrderMapper.class), mock(OrderExternalReferenceMapper.class), mock(DeviceService.class));
        DeviceInstance device = new DeviceInstance();
        device.deviceCode = "10000000";

        assertTrue(service.nextYkcTransactionNo(device, "01").matches("[0-9]{32}"));
    }

    @Test
    void orderViewExposesFeesLossRateSnapshotAndDerivedDuration() {
        ChargeOrderMapper orders = mock(ChargeOrderMapper.class);
        OrderExternalReferenceMapper references = mock(OrderExternalReferenceMapper.class);
        DeviceService devices = mock(DeviceService.class);
        OrderService service = service(orders, references, devices);
        ChargeOrder order = order();
        DeviceInstance device = new DeviceInstance();
        device.deviceCode = "12345678";
        OrderExternalReference reference = new OrderExternalReference();
        reference.externalReference = "TRANSACTION-1";
        when(devices.get(1L)).thenReturn(device);
        when(references.findByOrder(1L, "OCPP16J", OrderService.OCPP_TRANSACTION))
                .thenReturn(Optional.of(reference));

        OrderView view = service.view(order);

        assertEquals(order.orderNo, view.orderNo());
        assertEquals(3723L, view.durationSeconds());
        assertEquals(new BigDecimal("12.5000"), view.energy());
        assertEquals(new BigDecimal("8.5000"), view.electricityFee());
        assertEquals(new BigDecimal("4.0000"), view.serviceFee());
        assertEquals(new BigDecimal("0.2500"), view.lossEnergy());
        assertEquals("{\"modelNo\":\"0001\"}", view.rateSnapshot());
        assertEquals("TRANSACTION-1", view.transactionNo());
    }

    @Test
    void orderDetailIncludesFeeSegmentsAndLeavesExistingBillsShape() {
        OrderService orders = mock(OrderService.class);
        BillService bills = mock(BillService.class);
        ChargeBillingService billing = mock(ChargeBillingService.class);
        OrderController controller = new OrderController(orders, bills, billing, mock(OrderTelemetryService.class));
        ChargeOrder order = order();
        ChargeOrderSegment segment = new ChargeOrderSegment();
        segment.period = 2;
        when(orders.require(order.orderNo)).thenReturn(order);
        when(orders.view(order)).thenReturn(null);
        when(bills.byOrder(1L)).thenReturn(List.of());
        when(billing.segments(1L)).thenReturn(List.of(segment));

        Map<String, Object> detail = controller.detail(order.orderNo);

        assertTrue(detail.containsKey("order"));
        assertTrue(detail.containsKey("bills"));
        assertSame(segment, ((List<?>) detail.get("feeSegments")).getFirst());
    }

    @Test
    void duplicateLowerMeterPayloadCannotReduceOrderTotals() {
        ChargeOrderMapper orders = mock(ChargeOrderMapper.class);
        ChargeOrder order = order();
        order.status = ChargeOrder.Status.CHARGING;
        order.energy = new BigDecimal("5.0000");
        order.amount = new BigDecimal("10.0000");
        when(orders.lockByOrderNo(order.orderNo)).thenReturn(Optional.of(order));
        OrderService service = service(orders, mock(OrderExternalReferenceMapper.class), mock(DeviceService.class));

        service.applyMeter(order.orderNo, new BigDecimal("4.0000"), new BigDecimal("9.0000"));

        assertEquals(new BigDecimal("5.0000"), order.energy);
        assertEquals(new BigDecimal("10.0000"), order.amount);
    }

    private static OrderService service(
            ChargeOrderMapper orders, OrderExternalReferenceMapper references, DeviceService devices) {
        return new OrderService(orders, references, devices, mock(ChargeBillingService.class));
    }

    private static ChargeOrder order() {
        ChargeOrder order = new ChargeOrder();
        order.id = 1L;
        order.orderNo = "202611221000000000001";
        order.deviceId = 1L;
        order.deviceSn = "12345678";
        order.gunNo = "01";
        order.platformGunNo = "1234567801";
        order.protocol = "OCPP16J";
        order.status = ChargeOrder.Status.SETTLED;
        order.startTime = LocalDateTime.parse("2026-11-22T10:00:00");
        order.endTime = LocalDateTime.parse("2026-11-22T11:02:03");
        order.energy = new BigDecimal("12.5000");
        order.electricityFee = new BigDecimal("8.5000");
        order.serviceFee = new BigDecimal("4.0000");
        order.amount = new BigDecimal("12.5000");
        order.lossEnergy = new BigDecimal("0.2500");
        order.rateModelNo = "0001";
        order.rateSnapshot = "{\"modelNo\":\"0001\"}";
        order.amountSource = ChargeOrder.AMOUNT_SOURCE_ESTIMATE;
        order.createdAt = order.startTime.minusMinutes(1);
        return order;
    }
}
