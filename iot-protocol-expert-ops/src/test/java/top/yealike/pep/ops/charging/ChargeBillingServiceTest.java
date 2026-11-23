package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.ops.api.internal.dto.BillRequest;
import top.yealike.pep.ops.api.internal.dto.YkcEnergySegment;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.ChargeOrderSegment;
import top.yealike.pep.ops.charging.domain.FeeTotal;
import top.yealike.pep.ops.charging.domain.RateModel;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderSegmentMapper;
import top.yealike.pep.ops.charging.service.ChargeBillingService;
import top.yealike.pep.ops.charging.service.RateModelService;

class ChargeBillingServiceTest {

    @Test
    void ocppIncrementCrossingHalfHourIsAllocatedToBothRatePeriods() {
        RateModel model = model(0);
        model.segments = segmentsWith(0, 3, 1, 0);
        ChargeOrder order = order(model);
        order.startTime = LocalDateTime.parse("2026-11-22T00:29:00");
        order.createdAt = order.startTime;
        order.updatedAt = order.startTime;
        ChargeOrderSegmentMapper segmentMapper = mock(ChargeOrderSegmentMapper.class);
        ChargeBillingService service = service(model, segmentMapper);

        FeeTotal total =
                service.applyOcppMeter(order, new BigDecimal("2.0000"), LocalDateTime.parse("2026-11-22T00:31:00"));

        ArgumentCaptor<List<ChargeOrderSegment>> captor = listCaptor();
        org.mockito.Mockito.verify(segmentMapper).replace(eq(1L), captor.capture());
        List<ChargeOrderSegment> rows = captor.getValue();
        assertEquals(0, row(rows, 3).energy.compareTo(new BigDecimal("1.0000")));
        assertEquals(0, row(rows, 0).energy.compareTo(new BigDecimal("1.0000")));
        assertEquals(0, total.energy().compareTo(new BigDecimal("2.0000")));
        assertEquals(LocalDateTime.parse("2026-11-22T00:31:00"), order.updatedAt);
    }

    @Test
    void firstReadingUsesChargingStartInsteadOfLaterOrderUpdateTime() {
        RateModel model = model(0);
        List<String> segments = new ArrayList<>(Collections.nCopies(48, "2"));
        segments.set(0, "0");
        segments.set(1, "3");
        model.segments = String.join(",", segments);
        ChargeOrder order = order(model);
        order.startTime = LocalDateTime.parse("2026-11-22T00:00:00");
        order.createdAt = order.startTime;
        order.updatedAt = LocalDateTime.parse("2026-11-22T00:29:00");
        ChargeOrderSegmentMapper segmentMapper = mock(ChargeOrderSegmentMapper.class);
        ChargeBillingService service = service(model, segmentMapper);

        service.applyOcppMeter(order, new BigDecimal("2.0000"), LocalDateTime.parse("2026-11-22T00:31:00"));

        ArgumentCaptor<List<ChargeOrderSegment>> captor = listCaptor();
        org.mockito.Mockito.verify(segmentMapper).replace(eq(1L), captor.capture());
        List<ChargeOrderSegment> rows = captor.getValue();
        assertEquals(0, row(rows, 0).energy.compareTo(new BigDecimal("1.93548387")));
        assertEquals(0, row(rows, 3).energy.compareTo(new BigDecimal("0.06451613")));
    }

    @Test
    void repeatedOcppMeterReadingDoesNotAddLossOrFeesTwice() {
        RateModel model = model(10);
        ChargeOrder order = order(model);
        order.startTime = LocalDateTime.parse("2026-11-22T10:00:00");
        order.createdAt = order.startTime;
        order.updatedAt = order.startTime;
        ChargeOrderSegmentMapper segmentMapper = statefulSegments();
        ChargeBillingService service = service(model, segmentMapper);

        LocalDateTime firstSample = LocalDateTime.parse("2026-11-22T10:01:00");
        FeeTotal first = service.applyOcppMeter(order, new BigDecimal("2.0000"), firstSample);
        FeeTotal repeated = service.applyOcppMeter(order, new BigDecimal("2.0000"), firstSample.plusMinutes(1));

        assertEquals(0, first.lossEnergy().compareTo(new BigDecimal("0.2000")));
        assertEquals(0, repeated.lossEnergy().compareTo(new BigDecimal("0.2000")));
        assertEquals(0, repeated.electricityFee().compareTo(new BigDecimal("2.2000")));
        assertEquals(0, repeated.serviceFee().compareTo(new BigDecimal("1.1000")));
        assertEquals(0, repeated.amount().compareTo(new BigDecimal("3.3000")));
        assertEquals(0, repeated.energy().compareTo(new BigDecimal("2.0000")));
    }

    @Test
    void ocppIncrementCrossingMidnightUsesBothCalendarDayBoundaries() {
        RateModel model = model(0);
        List<String> segments = new ArrayList<>(Collections.nCopies(48, "2"));
        segments.set(47, "3");
        segments.set(0, "0");
        model.segments = String.join(",", segments);
        ChargeOrder order = order(model);
        order.startTime = LocalDateTime.parse("2026-11-22T23:59:00");
        order.createdAt = order.startTime;
        order.updatedAt = order.startTime;
        ChargeOrderSegmentMapper segmentMapper = mock(ChargeOrderSegmentMapper.class);
        ChargeBillingService service = service(model, segmentMapper);

        service.applyOcppMeter(order, new BigDecimal("2.0000"), LocalDateTime.parse("2026-11-23T00:01:00"));

        ArgumentCaptor<List<ChargeOrderSegment>> captor = listCaptor();
        org.mockito.Mockito.verify(segmentMapper).replace(eq(1L), captor.capture());
        assertEquals(0, row(captor.getValue(), 3).energy.compareTo(new BigDecimal("1.0000")));
        assertEquals(0, row(captor.getValue(), 0).energy.compareTo(new BigDecimal("1.0000")));
    }

    @Test
    void ykcSegmentLossesAreSummedAcrossAllFourPeriods() {
        RateModel model = model(0);
        ChargeOrder order = order(model);
        ChargeOrderSegmentMapper segmentMapper = mock(ChargeOrderSegmentMapper.class);
        ChargeBillingService service = service(model, segmentMapper);
        List<YkcEnergySegment> input = new ArrayList<>();
        for (int period = 0; period < 4; period++) {
            input.add(new YkcEnergySegment(
                    new BigDecimal("1.00000"), BigDecimal.ONE, new BigDecimal("0.1000"), new BigDecimal("1.1000")));
        }
        BillRequest request = new BillRequest(
                "YKC16",
                "12345678",
                "transaction",
                "01",
                LocalDateTime.parse("2026-11-22T10:00:00"),
                LocalDateTime.parse("2026-11-22T12:00:00"),
                null,
                new BigDecimal("4.0000"),
                new BigDecimal("4.4000"),
                null,
                input,
                0,
                1,
                null,
                null,
                null);

        FeeTotal total = service.applyYkcBill(order, request);

        assertEquals(0, total.lossEnergy().compareTo(new BigDecimal("0.4000")));
        assertEquals(0, total.energy().compareTo(new BigDecimal("4.0000")));
    }

    private static ChargeBillingService service(RateModel model, ChargeOrderSegmentMapper segmentMapper) {
        RateModelService rateModels = mock(RateModelService.class);
        when(rateModels.active()).thenReturn(model);
        ChargeOrderMapper orders = mock(ChargeOrderMapper.class);
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ChargeBillingService(rateModels, orders, segmentMapper, new ObjectMapper());
    }

    private static ChargeOrder order(RateModel model) {
        ChargeOrder order = new ChargeOrder();
        order.id = 1L;
        order.orderNo = "202611221000000000001";
        order.deviceId = 1L;
        order.deviceSn = "12345678";
        order.gunNo = "01";
        order.protocol = "OCPP16J";
        order.status = ChargeOrder.Status.CHARGING;
        order.energy = BigDecimal.ZERO;
        order.rateModelNo = model.modelNo;
        return order;
    }

    private static RateModel model(int lossRatio) {
        RateModel model = new RateModel();
        model.modelNo = "0001";
        model.sharpEnergy = new BigDecimal("3.00000");
        model.sharpService = new BigDecimal("0.30000");
        model.peakEnergy = new BigDecimal("2.00000");
        model.peakService = new BigDecimal("0.20000");
        model.flatEnergy = new BigDecimal("1.00000");
        model.flatService = new BigDecimal("0.50000");
        model.valleyEnergy = new BigDecimal("0.50000");
        model.valleyService = new BigDecimal("0.10000");
        model.lossRatio = lossRatio;
        model.segments = String.join(",", Collections.nCopies(48, "2"));
        model.active = true;
        return model;
    }

    private static String segmentsWith(int firstIndex, int firstValue, int secondIndex, int secondValue) {
        List<String> values = new ArrayList<>(Collections.nCopies(48, "2"));
        values.set(firstIndex, String.valueOf(firstValue));
        values.set(secondIndex, String.valueOf(secondValue));
        return String.join(",", values);
    }

    private static ChargeOrderSegment row(List<ChargeOrderSegment> rows, int period) {
        return rows.stream()
                .filter(candidate -> candidate.period == period)
                .findFirst()
                .orElseThrow();
    }

    private static ChargeOrderSegmentMapper statefulSegments() {
        ChargeOrderSegmentMapper mapper = mock(ChargeOrderSegmentMapper.class);
        AtomicReference<List<ChargeOrderSegment>> stored = new AtomicReference<>(List.of());
        when(mapper.findByOrderId(1L)).thenAnswer(invocation -> new ArrayList<>(stored.get()));
        doAnswer(invocation -> {
                    stored.set(new ArrayList<>(invocation.getArgument(1)));
                    return null;
                })
                .when(mapper)
                .replace(eq(1L), any());
        return mapper;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ChargeOrderSegment>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
