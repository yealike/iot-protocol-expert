package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.domain.RateModelEntity;
import top.yealike.ipe.protocol.repository.RateModelRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 计费模型管理。 */
@Service
public class RateModelService {

    private final RateModelRepository repository;
    private final ProtocolProperties properties;

    public RateModelService(RateModelRepository repository, ProtocolProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureDefault() {
        if (repository.count() > 0) {
            return;
        }
        RateModelEntity model = new RateModelEntity();
        model.setModelNo(properties.getDefaultRateModelNo());
        model.setSharpEnergy(new BigDecimal("1.20000"));
        model.setSharpService(new BigDecimal("0.40000"));
        model.setPeakEnergy(new BigDecimal("1.00000"));
        model.setPeakService(new BigDecimal("0.35000"));
        model.setFlatEnergy(new BigDecimal("0.80000"));
        model.setFlatService(new BigDecimal("0.30000"));
        model.setValleyEnergy(new BigDecimal("0.50000"));
        model.setValleyService(new BigDecimal("0.20000"));
        model.setLossRatio(0);
        model.setSegments(defaultSegments());
        model.setActive(true);
        model.setUpdatedAt(LocalDateTime.now());
        repository.save(model);
    }

    private static String defaultSegments() {
        List<String> segments = new ArrayList<>(48);
        // 00:00-06:00 谷，06:00-10:00 平，10:00-15:00 峰，15:00-18:00 平，18:00-21:00 尖，21:00-24:00 平
        for (int i = 0; i < 48; i++) {
            int hour = i / 2;
            if (hour < 6) {
                segments.add("3");
            } else if (hour < 10) {
                segments.add("2");
            } else if (hour < 15) {
                segments.add("1");
            } else if (hour < 18) {
                segments.add("2");
            } else if (hour < 21) {
                segments.add("0");
            } else {
                segments.add("2");
            }
        }
        return String.join(",", segments);
    }

    public RateModelEntity active() {
        return repository.findTopByActiveTrueOrderByUpdatedAtDesc()
                .or(() -> repository.findByModelNo(properties.getDefaultRateModelNo()))
                .orElseGet(() -> {
                    ensureDefault();
                    return repository.findByModelNo(properties.getDefaultRateModelNo())
                            .orElseThrow(() -> new IllegalStateException("默认计费模型初始化失败"));
                });
    }

    public RateModelEntity findByModelNo(String modelNo) {
        return repository.findByModelNo(modelNo).orElse(null);
    }

    public List<RateModelEntity> list() {
        return repository.findAllByOrderByModelNoAsc();
    }

    public List<Integer> segmentsOf(RateModelEntity model) {
        return Arrays.stream(model.getSegments().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    public RegistrationFrames.RateModel toProtocol(RateModelEntity model, String pileCode) {
        return new RegistrationFrames.RateModel(
                pileCode, model.getModelNo(),
                model.getSharpEnergy(), model.getSharpService(),
                model.getPeakEnergy(), model.getPeakService(),
                model.getFlatEnergy(), model.getFlatService(),
                model.getValleyEnergy(), model.getValleyService(),
                model.getLossRatio(), segmentsOf(model));
    }

    /** 桩上报的模型编号与平台当前生效模型是否一致。 */
    public boolean verify(String modelNo) {
        return active().getModelNo().equals(modelNo);
    }

    @Transactional
    public RateModelEntity save(String modelNo, BigDecimal sharpEnergy, BigDecimal sharpService,
                                BigDecimal peakEnergy, BigDecimal peakService,
                                BigDecimal flatEnergy, BigDecimal flatService,
                                BigDecimal valleyEnergy, BigDecimal valleyService,
                                int lossRatio, List<Integer> segments, boolean active) {
        if (segments == null || segments.size() != 48) {
            throw new IllegalArgumentException("时段费率号必须为 48 个");
        }
        new RegistrationFrames.RateModel("00000000000000",modelNo,sharpEnergy,sharpService,peakEnergy,peakService,flatEnergy,flatService,valleyEnergy,valleyService,lossRatio,segments);
        RateModelEntity model = repository.findByModelNo(modelNo).orElseGet(RateModelEntity::new);
        model.setModelNo(modelNo);
        model.setSharpEnergy(sharpEnergy);
        model.setSharpService(sharpService);
        model.setPeakEnergy(peakEnergy);
        model.setPeakService(peakService);
        model.setFlatEnergy(flatEnergy);
        model.setFlatService(flatService);
        model.setValleyEnergy(valleyEnergy);
        model.setValleyService(valleyService);
        model.setLossRatio(lossRatio);
        model.setSegments(segments.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        model.setUpdatedAt(LocalDateTime.now());
        if (active) {
            repository.findAll().forEach(other -> {
                if (!other.getModelNo().equals(modelNo)) {
                    other.setActive(false);
                    repository.save(other);
                }
            });
        }
        model.setActive(active);
        return repository.save(model);
    }
}