package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.ops.api.internal.dto.BillRequest;
import top.yealike.pep.ops.api.internal.dto.YkcEnergySegment;
import top.yealike.pep.ops.charging.domain.Bill;
import top.yealike.pep.ops.charging.domain.RateModel;
import top.yealike.pep.ops.charging.mapper.BillMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderSegmentMapper;
import top.yealike.pep.ops.charging.service.BillService;
import top.yealike.pep.ops.charging.service.ChargeBillingService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.charging.service.RateModelService;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;

class BillServiceTest {

    @Test
    void offlineYkcBillContainsCompleteFeesAtActiveRateSnapshot() {
        OfflineFixture fixture = offlineFixture();
        BillRequest request = offlineRequest(new BigDecimal("4.7500"));

        Bill bill = fixture.service().acceptYkc(request);

        assertTrue(bill.accepted);
        assertTrue(bill.partial);
        assertEquals("平台无对应订单，按离线账单入库", bill.rejectReason);
        assertNull(bill.orderId);
        assertNull(bill.orderNo);
        assertEquals(new BigDecimal("4.0000"), bill.energy);
        assertEquals(new BigDecimal("4.0700"), bill.electricityFee);
        assertEquals(new BigDecimal("1.5950"), bill.serviceFee);
        assertEquals(new BigDecimal("4.7500"), bill.amount);
        assertEquals(new BigDecimal("0.4000"), bill.lossEnergy);
        assertEquals("0001", bill.rateModelNo);
        assertNotNull(bill.rateSnapshot);
        assertTrue(bill.rateSnapshot.contains("\"modelNo\":\"0001\""));
        assertNotNull(bill.feeBreakdown);
        assertTrue(bill.feeBreakdown.startsWith("["));
        assertTrue(bill.feeBreakdown.contains("\"period\":2"));
        assertFalse(bill.feeBreakdown.isBlank());
        verifyNoInteractions(fixture.orderMapper());
        verifyNoInteractions(fixture.segmentMapper());
    }

    @Test
    void offlineYkcBillDerivesAmountFromSegmentsWhenDeviceTotalIsMissing() {
        OfflineFixture fixture = offlineFixture();
        BillRequest request = offlineRequest(null);

        Bill bill = fixture.service().acceptYkc(request);

        assertTrue(bill.accepted);
        assertTrue(bill.partial);
        assertEquals(new BigDecimal("4.4000"), bill.amount);
        assertEquals(new BigDecimal("4.0700"), bill.electricityFee);
        assertEquals(new BigDecimal("1.5950"), bill.serviceFee);
        verifyNoInteractions(fixture.orderMapper());
        verifyNoInteractions(fixture.segmentMapper());
    }

    @Test
    void offlineYkcBillKeepsReportedZeroAmount() {
        OfflineFixture fixture = offlineFixture();
        BillRequest request = offlineRequest(BigDecimal.ZERO);

        Bill bill = fixture.service().acceptYkc(request);

        assertEquals(0, bill.amount.compareTo(BigDecimal.ZERO));
        assertTrue(bill.electricityFee.signum() > 0);
        assertTrue(bill.serviceFee.signum() > 0);
    }

    private static OfflineFixture offlineFixture() {
        BillMapper bills = mock(BillMapper.class);
        DeviceService devices = mock(DeviceService.class);
        OrderService orders = mock(OrderService.class);
        RateModelService rates = mock(RateModelService.class);
        ChargeOrderMapper orderMapper = mock(ChargeOrderMapper.class);
        ChargeOrderSegmentMapper segmentMapper = mock(ChargeOrderSegmentMapper.class);
        ObjectMapper json = new ObjectMapper();
        DeviceInstance device = new DeviceInstance();
        device.id = 1L;
        device.deviceCode = "10000000";
        RateModel model = rateModel();
        when(devices.resolve("YKC16", "32010600000001")).thenReturn(device);
        when(bills.findByDeviceAndProtocolAndSourceTypeAndSourceTransactionNo(
                        1L, "YKC16", BillService.SOURCE_YKC_TRANSACTION, "transaction"))
                .thenReturn(Optional.empty());
        when(bills.save(any())).thenAnswer(invocation -> {
            Bill bill = invocation.getArgument(0);
            bill.id = 99L;
            return bill;
        });
        when(orders.findByExternal(1L, "YKC16", OrderService.YKC_TRANSACTION, "transaction"))
                .thenReturn(null);
        when(rates.active()).thenReturn(model);
        ChargeBillingService billing = new ChargeBillingService(rates, orderMapper, segmentMapper, json);
        BillService service = new BillService(bills, devices, orders, billing, json);
        return new OfflineFixture(service, orderMapper, segmentMapper);
    }

    private static BillRequest offlineRequest(BigDecimal totalAmount) {
        List<YkcEnergySegment> segments = List.of(
                new YkcEnergySegment(
                        new BigDecimal("1.10000"), BigDecimal.ONE, new BigDecimal("0.1000"), new BigDecimal("1.1000")),
                new YkcEnergySegment(
                        new BigDecimal("1.10000"), BigDecimal.ONE, new BigDecimal("0.1000"), new BigDecimal("1.1000")),
                new YkcEnergySegment(
                        new BigDecimal("1.10000"), BigDecimal.ONE, new BigDecimal("0.1000"), new BigDecimal("1.1000")),
                new YkcEnergySegment(
                        new BigDecimal("1.10000"), BigDecimal.ONE, new BigDecimal("0.1000"), new BigDecimal("1.1000")));
        return new BillRequest(
                "YKC16",
                "32010600000001",
                "transaction",
                "01",
                LocalDateTime.parse("2026-11-22T10:00:00"),
                LocalDateTime.parse("2026-11-22T12:00:00"),
                LocalDateTime.parse("2026-11-22T12:00:01"),
                new BigDecimal("4.0000"),
                totalAmount,
                new BigDecimal("0.4000"),
                segments,
                0,
                1,
                null,
                null,
                "raw");
    }

    private static RateModel rateModel() {
        RateModel model = new RateModel();
        model.modelNo = "0001";
        model.sharpEnergy = new BigDecimal("1.20000");
        model.sharpService = new BigDecimal("0.40000");
        model.peakEnergy = new BigDecimal("1.00000");
        model.peakService = new BigDecimal("0.35000");
        model.flatEnergy = new BigDecimal("1.00000");
        model.flatService = new BigDecimal("0.50000");
        model.valleyEnergy = new BigDecimal("0.50000");
        model.valleyService = new BigDecimal("0.20000");
        model.lossRatio = 0;
        model.segments = String.join(",", Collections.nCopies(48, "2"));
        model.active = true;
        return model;
    }

    private record OfflineFixture(
            BillService service, ChargeOrderMapper orderMapper, ChargeOrderSegmentMapper segmentMapper) {}
}
