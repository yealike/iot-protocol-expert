package top.yealike.pep.ops.charging.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.charging.domain.RateModel;
import top.yealike.pep.ops.charging.dto.RateModelView;
import top.yealike.pep.ops.charging.mapper.RateModelMapper;

@Service
public class RateModelService {

    private static final int[] DEFAULT_SEGMENTS = defaultSegments();
    private static final BigDecimal MAX_PRICE = new BigDecimal("42949.67295");
    private static final int PRICE_SCALE = 5;

    private final RateModelMapper models;

    public RateModelService(RateModelMapper models) {
        this.models = models;
    }

    @Transactional
    public RateModel active() {
        return models.findTopByActiveTrueOrderByUpdatedAtDesc()
                .or(() -> models.findByModelNo("0001"))
                .orElseGet(this::createDefault);
    }

    private RateModel createDefault() {
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
        return parseSegments(model.segments);
    }

    public static List<Integer> parseSegments(String segments) {
        return Arrays.stream(segments.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    public RateModelView view(RateModel model) {
        return new RateModelView(
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
    public RateModel save(RateModelView input) {
        ValidatedRate validated = validate(input);
        List<RateModel> locked = models.findAllForUpdate();
        RateModel model = locked.stream()
                .filter(candidate -> candidate.modelNo.equals(validated.modelNo()))
                .findFirst()
                .orElseGet(RateModel::new);
        if (model.active && !input.active()) {
            throw new IllegalArgumentException("当前生效费率不能直接停用，请先启用其他费率模型");
        }
        if (input.active()) {
            models.deactivateAllExcept(validated.modelNo());
        }
        model.modelNo = validated.modelNo();
        model.sharpEnergy = validated.sharpEnergy();
        model.sharpService = validated.sharpService();
        model.peakEnergy = validated.peakEnergy();
        model.peakService = validated.peakService();
        model.flatEnergy = validated.flatEnergy();
        model.flatService = validated.flatService();
        model.valleyEnergy = validated.valleyEnergy();
        model.valleyService = validated.valleyService();
        model.lossRatio = validated.lossRatio();
        model.segments = validated.segments();
        model.active = input.active();
        model.updatedAt = LocalDateTime.now();
        return models.save(model);
    }

    @Transactional
    public RateModel activate(String modelNo) {
        validateModelNo(modelNo);
        List<RateModel> locked = models.findAllForUpdate();
        RateModel model = locked.stream()
                .filter(candidate -> candidate.modelNo.equals(modelNo))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("费率模型不存在: " + modelNo));
        models.deactivateAllExcept(modelNo);
        model.active = true;
        model.updatedAt = LocalDateTime.now();
        return models.save(model);
    }

    private static ValidatedRate validate(RateModelView input) {
        if (input == null) {
            throw new IllegalArgumentException("费率模型不能为空");
        }
        validateModelNo(input.modelNo());
        if (input.segments() == null || input.segments().size() != 48) {
            throw new IllegalArgumentException("时段费率必须为48个半小时");
        }
        if (input.segments().stream().anyMatch(period -> period == null || period < 0 || period > 3)) {
            throw new IllegalArgumentException("费率时段必须为 0 至 3");
        }
        if (input.lossRatio() < 0 || input.lossRatio() > 100) {
            throw new IllegalArgumentException("计损比例必须为 0 至 100");
        }
        BigDecimal[] prices = {
            input.sharpEnergy(),
            input.sharpService(),
            input.peakEnergy(),
            input.peakService(),
            input.flatEnergy(),
            input.flatService(),
            input.valleyEnergy(),
            input.valleyService()
        };
        for (int index = 0; index < prices.length; index++) {
            prices[index] = validatePrice(prices[index]);
        }
        String segments = input.segments().stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return new ValidatedRate(
                input.modelNo(),
                prices[0],
                prices[1],
                prices[2],
                prices[3],
                prices[4],
                prices[5],
                prices[6],
                prices[7],
                input.lossRatio(),
                segments);
    }

    private static void validateModelNo(String modelNo) {
        if (modelNo == null || !modelNo.matches("\\d{4}")) {
            throw new IllegalArgumentException("费率模型编号必须为4位数字");
        }
    }

    private static BigDecimal validatePrice(BigDecimal price) {
        if (price == null
                || price.signum() < 0
                || price.compareTo(MAX_PRICE) > 0
                || price.stripTrailingZeros().scale() > PRICE_SCALE) {
            throw new IllegalArgumentException("费率须为非负数，最多五位小数且不能超过协议上限");
        }
        return price.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY);
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

    private record ValidatedRate(
            String modelNo,
            BigDecimal sharpEnergy,
            BigDecimal sharpService,
            BigDecimal peakEnergy,
            BigDecimal peakService,
            BigDecimal flatEnergy,
            BigDecimal flatService,
            BigDecimal valleyEnergy,
            BigDecimal valleyService,
            int lossRatio,
            String segments) {}
}
