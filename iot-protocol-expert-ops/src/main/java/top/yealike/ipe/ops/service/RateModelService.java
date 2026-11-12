package top.yealike.ipe.ops.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.domain.RateModel;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.repository.RateModelRepository;

@Service
public class RateModelService {

    private static final int[] DEFAULT_SEGMENTS = defaultSegments();

    private final RateModelRepository models;

    public RateModelService(RateModelRepository models) {
        this.models = models;
    }

    public RateModel active() {
        return models.findTopByActiveTrueOrderByUpdatedAtDesc()
                .or(() -> models.findByModelNo("0001"))
                .orElseGet(this::createDefault);
    }

    @Transactional
    protected RateModel createDefault() {
        RateModel model = new RateModel();
        model.modelNo = "0001";
        model.sharpEnergy = new BigDecimal("1.20000");
        model.sharpService = new BigDecimal("0.40000");
        model.peakEnergy = new BigDecimal("1.00000");
        model.peakService = new BigDecimal("0.35000");
        model.flatEnergy = new BigDecimal("0.80000");
        model.flatService = new BigDecimal("0.30000");
        model.valleyEnergy = new BigDecimal("0.50000");
        model.valleyService = new BigDecimal("0.20000");
        model.lossRatio = 0;
        model.segments = Arrays.stream(DEFAULT_SEGMENTS)
                .mapToObj(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        model.active = true;
        model.updatedAt = LocalDateTime.now();
        return models.save(model);
    }

    public boolean verify(String modelNo) {
        return active().modelNo.equals(modelNo);
    }

    public BigDecimal pricePerKwh(LocalDateTime time) {
        RateModel model = active();
        int index = Math.min(47, Math.max(0, time.getHour() * 2 + (time.getMinute() >= 30 ? 1 : 0)));
        List<Integer> segments = segmentsOf(model);
        int period = index < segments.size() ? segments.get(index) : 2;
        return switch (period) {
            case 0 -> model.sharpEnergy.add(model.sharpService);
            case 1 -> model.peakEnergy.add(model.peakService);
            case 3 -> model.valleyEnergy.add(model.valleyService);
            default -> model.flatEnergy.add(model.flatService);
        };
    }

    public List<Integer> segmentsOf(RateModel model) {
        return Arrays.stream(model.segments.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    public OpsDtos.RateModelView view(RateModel model) {
        return new OpsDtos.RateModelView(
                model.modelNo,
                model.sharpEnergy,
                model.sharpService,
                model.peakEnergy,
                model.peakService,
                model.flatEnergy,
                model.flatService,
                model.valleyEnergy,
                model.valleyService,
                model.lossRatio,
                segmentsOf(model),
                model.active,
                model.updatedAt);
    }

    public List<RateModel> list() {
        return models.findAll();
    }

    @Transactional
    public RateModel save(OpsDtos.RateModelView input) {
        if (input.modelNo() == null || !input.modelNo().matches("\\d{4}")) {
            throw new IllegalArgumentException("费率模型编号必须为4位数字");
        }
        if (input.segments() == null || input.segments().size() != 48) {
            throw new IllegalArgumentException("时段费率必须为48个半小时");
        }
        RateModel model = models.findByModelNo(input.modelNo()).orElseGet(RateModel::new);
        model.modelNo = input.modelNo();
        model.sharpEnergy = input.sharpEnergy();
        model.sharpService = input.sharpService();
        model.peakEnergy = input.peakEnergy();
        model.peakService = input.peakService();
        model.flatEnergy = input.flatEnergy();
        model.flatService = input.flatService();
        model.valleyEnergy = input.valleyEnergy();
        model.valleyService = input.valleyService();
        model.lossRatio = input.lossRatio();
        model.segments = input.segments().stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        if (input.active()) {
            models.findAll().forEach(other -> {
                if (!other.modelNo.equals(model.modelNo)) {
                    other.active = false;
                    models.save(other);
                }
            });
        }
        model.active = input.active();
        model.updatedAt = LocalDateTime.now();
        return models.save(model);
    }

    private static int[] defaultSegments() {
        int[] segments = new int[48];
        for (int i = 0; i < 48; i++) {
            int hour = i / 2;
            if (hour < 6) segments[i] = 3;
            else if (hour < 10) segments[i] = 2;
            else if (hour < 15) segments[i] = 1;
            else if (hour < 18) segments[i] = 2;
            else if (hour < 21) segments[i] = 0;
            else segments[i] = 2;
        }
        return segments;
    }
}
