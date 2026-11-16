package top.yealike.ipe.ops.service;

import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.service.DevicePropertyService;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.telemetry.OrderTelemetryService;

class OcppTelemetryTest {
    @Test
    void ignoresTransactionBelongingToAnotherDevice() {
        var telemetry = mock(OrderTelemetryService.class);
        var devices = mock(DeviceService.class);
        var orders = mock(OrderService.class);
        var properties = mock(DevicePropertyService.class);
        var service = new OcppBusinessService(
                telemetry, devices, orders, properties, mock(BillService.class), mock(RateModelService.class));
        var device = new DeviceInstance();
        device.id = 1L;
        var order = new ChargeOrder();
        order.deviceId = 2L;
        order.gunNo = "01";
        order.status = ChargeOrder.Status.CHARGING;
        when(devices.resolve("OCPP16J", "pile")).thenReturn(device);
        when(orders.byOcppTransactionId(3)).thenReturn(order);
        service.meterValues(
                new OpsDtos.MeterValuesRequest("pile", 1, 3, null, BigDecimal.TEN, null, null, null, null, null));
        verifyNoInteractions(telemetry, properties);
        verify(orders, never()).applyMeter(anyString(), any(), any());
    }

    @Test
    void recordsDeviceTimestampAndReportedMeasurements() {
        var telemetry = mock(OrderTelemetryService.class);
        var devices = mock(DeviceService.class);
        var orders = mock(OrderService.class);
        var properties = mock(DevicePropertyService.class);
        var rates = mock(RateModelService.class);
        var service = new OcppBusinessService(telemetry, devices, orders, properties, mock(BillService.class), rates);
        var device = new DeviceInstance();
        device.id = 1L;
        var order = new ChargeOrder();
        order.deviceId = 1L;
        order.orderNo = "ORDER-1";
        order.gunNo = "01";
        order.status = ChargeOrder.Status.CHARGING;
        order.energy = BigDecimal.ZERO;
        when(devices.resolve("OCPP16J", "pile")).thenReturn(device);
        when(orders.byOcppTransactionId(3)).thenReturn(order);
        when(rates.pricePerKwh(any())).thenReturn(BigDecimal.ONE);
        service.meterValues(new OpsDtos.MeterValuesRequest(
                "pile",
                1,
                3,
                null,
                BigDecimal.TEN,
                BigDecimal.ONE,
                50,
                new BigDecimal("7.5"),
                BigDecimal.ZERO,
                "2026-01-01T00:00:00Z"));
        verify(telemetry)
                .record(
                        eq(order),
                        eq(java.time.Instant.parse("2026-01-01T00:00:00Z")),
                        eq("DEVICE"),
                        eq(BigDecimal.TEN),
                        eq(BigDecimal.ONE),
                        eq(new BigDecimal("7.5")),
                        eq(BigDecimal.ZERO),
                        isNull(),
                        eq(BigDecimal.valueOf(50)));
    }
}
