package top.yealike.pep.ops.charging.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.ChargeOrderSegment;
import top.yealike.pep.ops.charging.domain.RateModel;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderSegmentMapper;

/** 将当前费率冻结进订单，并把设备账单或 OCPP 电表增量拆成尖峰平谷费用。 */
@Service
public class ChargeBillingService {

    private static final int PERIOD_COUNT = 4;
    private static final int HALF_HOURS = 48;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final RateModelService rateModels;
    private final ChargeOrderMapper orders;
    private final ChargeOrderSegmentMapper segments;
    private final ObjectMapper json;

    public ChargeBillingService(
            RateModelService rateModels,
            ChargeOrderMapper orders,
            ChargeOrderSegmentMapper segments,
            ObjectMapper json) {
        this.rateModels = rateModels;
        this.orders = orders;
        this.segments = segments;
        this.json = json;
    }

    public record SegmentRate(int period, BigDecimal electricityPrice, BigDecimal servicePrice) {}

    public record RateSnapshot(String modelNo, int lossRatio, List<Integer> timeSegments, List<SegmentRate> rates) {}

    public record FeeTotal(
            BigDecimal energy,
            BigDecimal electricityFee,
            BigDecimal serviceFee,
            BigDecimal amount,
            BigDecimal lossEnergy,
            boolean partial,
            String breakdown) {}

    public record YkcBillCalculation(FeeTotal total, List<ChargeOrderSegment> segments) {}

    @Transactional
    public RateSnapshot currentSnapshot() {
        RateModel model = rateModels.active();
        return snapshotOf(model);
    }

    public String json(RateSnapshot snapshot) {
        return json.writeValueAsString(snapshot);
    }

    public String json(FeeTotal total) {
        return json.writeValueAsString(total);
    }

    public RateSnapshot parse(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return currentSnapshot();
        }
        return json.readValue(snapshot, RateSnapshot.class);
    }

    public List<ChargeOrderSegment> initialSegments(ChargeOrder order, RateSnapshot snapshot) {
        List<ChargeOrderSegment> rows = new ArrayList<>(PERIOD_COUNT);
        for (SegmentRate rate : snapshot.rates()) {
            rows.add(segment(order, rate, ChargeOrder.AMOUNT_SOURCE_ESTIMATE, LocalDateTime.now()));
        }
        return rows;
    }

    @Transactional
    public void initializeOrder(ChargeOrder order) {
        segments.replace(order.id, initialSegments(order, parse(order.rateSnapshot)));
    }

    @Transactional
    public FeeTotal applyOcppMeter(ChargeOrder order, BigDecimal cumulativeEnergy, LocalDateTime sampleTime) {
        RateSnapshot snapshot = parse(order.rateSnapshot);
        List<ChargeOrderSegment> rows = new ArrayList<>(segments.findByOrderId(order.id));
        if (rows.isEmpty()) {
            rows = new ArrayList<>(initialSegments(order, snapshot));
        }
        LocalDateTime effectiveSampleTime = sampleTime == null ? LocalDateTime.now() : sampleTime;
        BigDecimal storedEnergy = zero(order.energy).max(sumEnergy(rows));
        BigDecimal effectiveEnergy = zero(cumulativeEnergy).max(storedEnergy);
        BigDecimal increment = effectiveEnergy.subtract(storedEnergy);
        if (increment.signum() > 0) {
            boolean lateSample = storedEnergy.signum() > 0
                    && order.updatedAt != null
                    && !effectiveSampleTime.isAfter(order.updatedAt);
            if (lateSample) {
                addEnergy(
                        rowForPeriod(rows, periodAt(snapshot, order.updatedAt), order, snapshot),
                        increment,
                        snapshot.lossRatio());
            } else {
                allocateEnergy(
                        rows,
                        snapshot,
                        order,
                        allocationStart(order, effectiveSampleTime, storedEnergy.signum() > 0),
                        effectiveSampleTime,
                        increment);
            }
        }
        segments.replace(order.id, rows);
        FeeTotal total = aggregate(rows, effectiveEnergy, false, ChargeOrder.AMOUNT_SOURCE_ESTIMATE);
        apply(order, total, snapshot.modelNo());
        if (order.updatedAt == null || effectiveSampleTime.isAfter(order.updatedAt)) {
            order.updatedAt = effectiveSampleTime;
        }
        orders.save(order);
        return total;
    }

    @Transactional
    public FeeTotal applyYkcBill(ChargeOrder order, OpsDtos.BillRequest request) {
        RateSnapshot snapshot = parse(order.rateSnapshot);
        YkcBillCalculation calculation = calculateYkcBill(snapshot, request);
        List<ChargeOrderSegment> rows = calculation.segments();
        rows.forEach(row -> row.orderId = order.id);
        segments.replace(order.id, rows);
        FeeTotal total = calculation.total();
        apply(order, total, snapshot.modelNo());
        order.amountSource = ChargeOrder.AMOUNT_SOURCE_DEVICE;
        orders.save(order);
        return total;
    }

    /** 按给定费率快照计算 YKC 四段账单；不要求已存在平台订单，也不写入分段表。 */
    public YkcBillCalculation calculateYkcBill(RateSnapshot snapshot, OpsDtos.BillRequest request) {
        List<OpsDtos.YkcEnergySegment> incoming = request.segments() == null ? List.of() : request.segments();
        List<ChargeOrderSegment> rows = new ArrayList<>(PERIOD_COUNT);
        BigDecimal deviceTotal = zero(request.totalAmount());
        BigDecimal segmentLossTotal = BigDecimal.ZERO;
        for (int period = 0; period < PERIOD_COUNT; period++) {
            SegmentRate rate = rateAt(snapshot, period);
            OpsDtos.YkcEnergySegment source = period < incoming.size() ? incoming.get(period) : null;
            BigDecimal energy =
                    source == null ? BigDecimal.ZERO : zero(source.energy()).max(BigDecimal.ZERO);
            BigDecimal loss =
                    source == null ? BigDecimal.ZERO : zero(source.lossEnergy()).max(BigDecimal.ZERO);
            segmentLossTotal = segmentLossTotal.add(loss);
            ChargeOrderSegment row = segment(rate, ChargeOrder.AMOUNT_SOURCE_DEVICE, LocalDateTime.now());
            row.energy = energy.setScale(4, ROUNDING);
            row.lossEnergy = loss.setScale(4, ROUNDING);
            rows.add(row);
        }
        BigDecimal lossTotal = zero(request.lossTotalEnergy());
        if (lossTotal.signum() == 0 || lossTotal.compareTo(segmentLossTotal) < 0) {
            lossTotal = segmentLossTotal;
        } else if (lossTotal.compareTo(segmentLossTotal) > 0) {
            distributeUnallocatedLoss(
                    rows,
                    snapshot,
                    lossTotal.subtract(segmentLossTotal),
                    request.endTime() == null ? request.tradeTime() : request.endTime());
        }

        BigDecimal estimatedTotal = BigDecimal.ZERO;
        for (int period = 0; period < PERIOD_COUNT; period++) {
            OpsDtos.YkcEnergySegment source = period < incoming.size() ? incoming.get(period) : null;
            ChargeOrderSegment row = rows.get(period);
            BigDecimal pricedEnergy = row.energy.add(row.lossEnergy);
            row.electricityFee = pricedEnergy.multiply(row.electricityPrice).setScale(4, ROUNDING);
            row.serviceFee = pricedEnergy.multiply(row.servicePrice).setScale(4, ROUNDING);
            BigDecimal estimated = row.electricityFee.add(row.serviceFee);
            if (source != null && source.amount() != null) {
                row.totalAmount = source.amount().setScale(4, ROUNDING);
            } else if (source != null && source.unitPrice() != null) {
                row.totalAmount = pricedEnergy.multiply(source.unitPrice()).setScale(4, ROUNDING);
            } else {
                row.totalAmount = estimated;
            }
            estimatedTotal = estimatedTotal.add(estimated);
        }
        BigDecimal reportedEnergy = zero(request.totalEnergy());
        if (reportedEnergy.signum() == 0) {
            reportedEnergy = rows.stream().map(row -> row.energy).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal segmentTotal = rows.stream().map(row -> row.totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean deviceTotalReported = request.totalAmount() != null;
        BigDecimal finalTotal = deviceTotalReported ? deviceTotal : segmentTotal;
        boolean partial = deviceTotalReported
                && estimatedTotal.setScale(4, ROUNDING).compareTo(deviceTotal.setScale(4, ROUNDING)) != 0;
        FeeTotal total = aggregate(rows, reportedEnergy, partial, ChargeOrder.AMOUNT_SOURCE_DEVICE);
        total = new FeeTotal(
                total.energy(),
                total.electricityFee(),
                total.serviceFee(),
                finalTotal,
                lossTotal,
                partial,
                total.breakdown());
        return new YkcBillCalculation(total, List.copyOf(rows));
    }

    public List<ChargeOrderSegment> segments(Long orderId) {
        return segments.findByOrderId(orderId);
    }

    private void apply(ChargeOrder order, FeeTotal total, String modelNo) {
        order.energy = total.energy();
        order.electricityFee = total.electricityFee();
        order.serviceFee = total.serviceFee();
        order.amount = total.amount();
        order.lossEnergy = total.lossEnergy();
        order.feeBreakdown = total.breakdown();
        if (order.rateModelNo == null) {
            order.rateModelNo = modelNo;
        }
    }

    private static void distributeUnallocatedLoss(
            List<ChargeOrderSegment> rows,
            RateSnapshot snapshot,
            BigDecimal unallocated,
            LocalDateTime allocationTime) {
        if (unallocated == null || unallocated.signum() <= 0) {
            return;
        }
        BigDecimal totalEnergy = rows.stream().map(row -> row.energy).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalEnergy.signum() <= 0) {
            int period = periodAt(snapshot, allocationTime == null ? LocalDateTime.now() : allocationTime);
            ChargeOrderSegment row = rows.stream()
                    .filter(candidate -> candidate.period == period)
                    .findFirst()
                    .orElse(rows.get(2));
            row.lossEnergy = row.lossEnergy.add(unallocated.setScale(4, ROUNDING));
            return;
        }

        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < rows.size(); index++) {
            ChargeOrderSegment row = rows.get(index);
            BigDecimal portion;
            if (index == rows.size() - 1) {
                portion = unallocated.subtract(allocated);
            } else {
                portion = unallocated
                        .multiply(row.energy)
                        .divide(totalEnergy, 8, ROUNDING)
                        .setScale(4, ROUNDING);
                allocated = allocated.add(portion);
            }
            row.lossEnergy = row.lossEnergy.add(portion);
        }
    }

    private FeeTotal aggregate(
            List<ChargeOrderSegment> rows, BigDecimal expectedEnergy, boolean partial, String source) {
        BigDecimal segmentEnergy = rows.stream().map(row -> row.energy).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal energy = expectedEnergy.signum() > 0 ? expectedEnergy : segmentEnergy;
        BigDecimal electricity = rows.stream().map(row -> row.electricityFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal service = rows.stream().map(row -> row.serviceFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = rows.stream().map(row -> row.totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loss = rows.stream().map(row -> row.lossEnergy).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<FeeLine> lines = rows.stream()
                .map(row -> new FeeLine(
                        row.period,
                        row.energy.stripTrailingZeros(),
                        row.lossEnergy.stripTrailingZeros(),
                        row.electricityPrice.stripTrailingZeros(),
                        row.servicePrice.stripTrailingZeros(),
                        row.electricityFee.stripTrailingZeros(),
                        row.serviceFee.stripTrailingZeros(),
                        row.totalAmount.stripTrailingZeros(),
                        source))
                .toList();
        return new FeeTotal(
                energy.setScale(4, ROUNDING),
                electricity.setScale(4, ROUNDING),
                service.setScale(4, ROUNDING),
                amount.setScale(4, ROUNDING),
                loss.setScale(4, ROUNDING),
                partial,
                json.writeValueAsString(lines));
    }

    private record FeeLine(
            int period,
            BigDecimal energy,
            BigDecimal lossEnergy,
            BigDecimal electricityPrice,
            BigDecimal servicePrice,
            BigDecimal electricityFee,
            BigDecimal serviceFee,
            BigDecimal amount,
            String source) {}

    private ChargeOrderSegment rowForPeriod(
            List<ChargeOrderSegment> rows, int period, ChargeOrder order, RateSnapshot snapshot) {
        return rows.stream().filter(row -> row.period == period).findFirst().orElseGet(() -> {
            ChargeOrderSegment created =
                    segment(order, rateAt(snapshot, period), ChargeOrder.AMOUNT_SOURCE_ESTIMATE, LocalDateTime.now());
            rows.add(created);
            return created;
        });
    }

    private void allocateEnergy(
            List<ChargeOrderSegment> rows,
            RateSnapshot snapshot,
            ChargeOrder order,
            LocalDateTime from,
            LocalDateTime to,
            BigDecimal energy) {
        if (from == null || to == null || !from.isBefore(to)) {
            addEnergy(
                    rowForPeriod(rows, periodAt(snapshot, to == null ? LocalDateTime.now() : to), order, snapshot),
                    energy,
                    snapshot.lossRatio());
            return;
        }
        BigDecimal totalDuration = durationUnits(Duration.between(from, to));
        BigDecimal remaining = energy;
        LocalDateTime cursor = from;
        while (cursor.isBefore(to)) {
            LocalDateTime boundary = cursor.withSecond(0).withNano(0);
            if (cursor.getMinute() < 30) {
                boundary = boundary.withMinute(30);
            } else {
                boundary = boundary.withMinute(0).plusHours(1);
            }
            LocalDateTime end = to.isBefore(boundary) ? to : boundary;
            boolean last = !end.isBefore(to);
            BigDecimal portion = last
                    ? remaining
                    : energy.multiply(durationUnits(Duration.between(cursor, end)))
                            .divide(totalDuration, 8, ROUNDING);
            addEnergy(rowForPeriod(rows, periodAt(snapshot, cursor), order, snapshot), portion, snapshot.lossRatio());
            remaining = remaining.subtract(portion);
            cursor = end;
        }
    }

    private static void addEnergy(ChargeOrderSegment row, BigDecimal energy, int lossRatio) {
        if (energy.signum() <= 0) {
            return;
        }
        row.energy = row.energy.add(energy);
        if (lossRatio > 0) {
            row.lossEnergy = row.lossEnergy.add(
                    energy.multiply(BigDecimal.valueOf(lossRatio)).divide(HUNDRED, 8, ROUNDING));
        }
        recalculate(row, ChargeOrder.AMOUNT_SOURCE_ESTIMATE);
    }

    private static void recalculate(ChargeOrderSegment row, String source) {
        BigDecimal pricedEnergy = row.energy.add(row.lossEnergy);
        row.electricityFee = pricedEnergy.multiply(row.electricityPrice).setScale(4, ROUNDING);
        row.serviceFee = pricedEnergy.multiply(row.servicePrice).setScale(4, ROUNDING);
        row.totalAmount = row.electricityFee.add(row.serviceFee);
        row.source = source;
    }

    private static ChargeOrderSegment segment(ChargeOrder order, SegmentRate rate, String source, LocalDateTime now) {
        ChargeOrderSegment row = segment(rate, source, now);
        row.orderId = order.id;
        return row;
    }

    private static ChargeOrderSegment segment(SegmentRate rate, String source, LocalDateTime now) {
        ChargeOrderSegment row = new ChargeOrderSegment();
        row.period = rate.period();
        row.electricityPrice = rate.electricityPrice();
        row.servicePrice = rate.servicePrice();
        row.source = source;
        row.createdAt = now;
        return row;
    }

    private static RateSnapshot snapshotOf(RateModel model) {
        List<Integer> times = RateModelService.parseSegments(model.segments);
        if (times.size() != HALF_HOURS) {
            throw new IllegalStateException("费率模型时段必须为48个半小时: " + model.modelNo);
        }
        return new RateSnapshot(
                model.modelNo,
                model.lossRatio,
                List.copyOf(times),
                List.of(
                        new SegmentRate(0, model.sharpEnergy, model.sharpService),
                        new SegmentRate(1, model.peakEnergy, model.peakService),
                        new SegmentRate(2, model.flatEnergy, model.flatService),
                        new SegmentRate(3, model.valleyEnergy, model.valleyService)));
    }

    private static SegmentRate rateAt(RateSnapshot snapshot, int period) {
        return snapshot.rates().stream()
                .filter(rate -> rate.period() == period)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("费率快照缺少时段: " + period));
    }

    private static int periodAt(RateSnapshot snapshot, LocalDateTime time) {
        int index = Math.min(HALF_HOURS - 1, Math.max(0, time.getHour() * 2 + (time.getMinute() >= 30 ? 1 : 0)));
        return index < snapshot.timeSegments().size() ? snapshot.timeSegments().get(index) : 2;
    }

    private static LocalDateTime allocationStart(
            ChargeOrder order, LocalDateTime sampleTime, boolean hasAllocatedEnergy) {
        LocalDateTime from = order.startTime == null ? order.createdAt : order.startTime;
        if (hasAllocatedEnergy
                && order.updatedAt != null
                && !order.updatedAt.isAfter(sampleTime)
                && (from == null || order.updatedAt.isAfter(from))) {
            from = order.updatedAt;
        }
        return from == null || from.isAfter(sampleTime) ? sampleTime : from;
    }

    private static BigDecimal durationUnits(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private static BigDecimal sumEnergy(List<ChargeOrderSegment> rows) {
        return rows.stream().map(row -> zero(row.energy)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal zero(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }
}
