package top.yealike.ipe.simulator.runtime;

import java.math.*;
import java.time.*;
import java.util.*;
import top.yealike.ipe.protocol.codec.frames.OperationFrames.EnergySegment;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames.RateModel;

/** Per-order tariff snapshot. Split integration at each half-hour, including midnight. */
public final class ChargingLedger {
    private static final BigDecimal HOUR_NANOS = new BigDecimal("3600000000000");
    private final RateModel model;
    private final BigDecimal[] energy = zeros(), lossEnergy = zeros(), electricity = zeros(), service = zeros();

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    public ChargingLedger(RateModel model) {
        validate(model);
        this.model = model;
    }

    public static void validate(RateModel m) {
        if (m == null || m.modelNo() == null || !m.modelNo().matches("\\d{4}"))
            throw new IllegalArgumentException("模型编号须为4位数字");
        if (m.segments() == null
                || m.segments().size() != 48
                || m.segments().stream().anyMatch(x -> x == null || x < 0 || x > 3))
            throw new IllegalArgumentException("必须提供48个0~3的半小时时段");
        if (m.lossRatio() < 0 || m.lossRatio() > 100) throw new IllegalArgumentException("计损比例须为0~100");
        for (BigDecimal p : List.of(
                m.sharpEnergy(),
                m.sharpService(),
                m.peakEnergy(),
                m.peakService(),
                m.flatEnergy(),
                m.flatService(),
                m.valleyEnergy(),
                m.valleyService()))
            if (p.signum() < 0 || p.scale() > 5 || p.compareTo(new BigDecimal("42949.67295")) > 0)
                throw new IllegalArgumentException("费率须为非负、最多5位小数");
    }

    public RateModel model() {
        return model;
    }

    public static int period(RateModel m, LocalDateTime t) {
        return m.segments().get(t.getHour() * 2 + t.getMinute() / 30);
    }

    public static BigDecimal energyPrice(RateModel m, int p) {
        return switch (p) {
            case 0 -> m.sharpEnergy();
            case 1 -> m.peakEnergy();
            case 2 -> m.flatEnergy();
            case 3 -> m.valleyEnergy();
            default -> throw new IllegalArgumentException("费率号");
        };
    }

    public static BigDecimal servicePrice(RateModel m, int p) {
        return switch (p) {
            case 0 -> m.sharpService();
            case 1 -> m.peakService();
            case 2 -> m.flatService();
            case 3 -> m.valleyService();
            default -> throw new IllegalArgumentException("费率号");
        };
    }

    public void integrate(LocalDateTime from, LocalDateTime to, BigDecimal powerKw) {
        if (to.isBefore(from) || powerKw.signum() < 0) throw new IllegalArgumentException("时间/功率错误");
        while (from.isBefore(to)) {
            LocalDateTime boundary = from.withSecond(0).withNano(0).withMinute(from.getMinute() < 30 ? 30 : 0);
            if (from.getMinute() >= 30) boundary = boundary.plusHours(1);
            LocalDateTime end = to.isBefore(boundary) ? to : boundary;
            int p = period(model, from);
            BigDecimal delta = powerKw.multiply(
                            BigDecimal.valueOf(Duration.between(from, end).toNanos()))
                    .divide(HOUR_NANOS, 16, RoundingMode.HALF_UP);
            BigDecimal loss = delta.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(model.lossRatio(), 2)));
            energy[p] = energy[p].add(delta);
            lossEnergy[p] = lossEnergy[p].add(loss);
            electricity[p] = electricity[p].add(loss.multiply(energyPrice(model, p)));
            service[p] = service[p].add(loss.multiply(servicePrice(model, p)));
            from = end;
        }
    }

    private static BigDecimal q(BigDecimal n) {
        return n.setScale(4, RoundingMode.HALF_UP);
    }

    public EnergySegment segment(int p) {
        return new EnergySegment(
                energyPrice(model, p).add(servicePrice(model, p)),
                q(energy[p]),
                q(lossEnergy[p]),
                q(electricity[p].add(service[p])));
    }

    public BigDecimal energy() {
        return java.util.stream.IntStream.range(0, 4)
                .mapToObj(p -> segment(p).energy())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal lossEnergy() {
        return java.util.stream.IntStream.range(0, 4)
                .mapToObj(p -> segment(p).lossEnergy())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal amount() {
        return java.util.stream.IntStream.range(0, 4)
                .mapToObj(p -> segment(p).amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void checkpoint(Properties properties, String prefix) {
        properties.setProperty(
                prefix + "model",
                java.util.HexFormat.of()
                        .formatHex(top.yealike.ipe.protocol.codec.frames.RegistrationFrames.encodeRateModel(model)));
        for (int i = 0; i < 4; i++) {
            properties.setProperty(prefix + "energy." + i, energy[i].toPlainString());
            properties.setProperty(prefix + "loss." + i, lossEnergy[i].toPlainString());
            properties.setProperty(prefix + "electricity." + i, electricity[i].toPlainString());
            properties.setProperty(prefix + "service." + i, service[i].toPlainString());
        }
    }

    public static ChargingLedger restore(Properties p, String prefix) {
        var l = new ChargingLedger(top.yealike.ipe.protocol.codec.frames.RegistrationFrames.decodeRateModel(
                java.util.HexFormat.of().parseHex(p.getProperty(prefix + "model"))));
        for (int i = 0; i < 4; i++) {
            l.energy[i] = new BigDecimal(p.getProperty(prefix + "energy." + i));
            l.lossEnergy[i] = new BigDecimal(p.getProperty(prefix + "loss." + i));
            l.electricity[i] = new BigDecimal(p.getProperty(prefix + "electricity." + i));
            l.service[i] = new BigDecimal(p.getProperty(prefix + "service." + i));
        }
        return l;
    }

    public List<Map<String, Object>> view() {
        return java.util.stream.IntStream.range(0, 4)
                .mapToObj(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("period", p);
                    row.put("name", List.of("尖", "峰", "平", "谷").get(p));
                    row.put("energyPrice", energyPrice(model, p));
                    row.put("servicePrice", servicePrice(model, p));
                    row.put("energy", segment(p).energy());
                    row.put("lossEnergy", segment(p).lossEnergy());
                    row.put("amount", segment(p).amount());
                    row.put("electricityFee", q(electricity[p]));
                    row.put("serviceFee", segment(p).amount().subtract(q(electricity[p])));
                    return row;
                })
                .toList();
    }
}
