package top.yealike.ipe.protocol.api;

import top.yealike.ipe.protocol.domain.RateModelEntity;
import top.yealike.ipe.protocol.service.RateModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 计费模型查询与设置。
 */
@RestController
@RequestMapping("/api/rate-models")
public class RateModelController {

    private final RateModelService rateModelService;

    public RateModelController(RateModelService rateModelService) {
        this.rateModelService = rateModelService;
    }

    @GetMapping
    public List<RateModelEntity> list() {
        return rateModelService.list();
    }

    @PostMapping
    public RateModelEntity save(@RequestBody RateModelRequest request) {
        return rateModelService.save(request.modelNo(),
                request.sharpEnergy(), request.sharpService(),
                request.peakEnergy(), request.peakService(),
                request.flatEnergy(), request.flatService(),
                request.valleyEnergy(), request.valleyService(),
                request.lossRatio(), request.segments(), request.active());
    }

    public record RateModelRequest(String modelNo,
                                   BigDecimal sharpEnergy, BigDecimal sharpService,
                                   BigDecimal peakEnergy, BigDecimal peakService,
                                   BigDecimal flatEnergy, BigDecimal flatService,
                                   BigDecimal valleyEnergy, BigDecimal valleyService,
                                   int lossRatio, List<Integer> segments, boolean active) {
        public RateModelRequest {
            modelNo = modelNo == null || modelNo.isBlank() ? "0001" : modelNo;
            sharpEnergy = zero(sharpEnergy);
            sharpService = zero(sharpService);
            peakEnergy = zero(peakEnergy);
            peakService = zero(peakService);
            flatEnergy = zero(flatEnergy);
            flatService = zero(flatService);
            valleyEnergy = zero(valleyEnergy);
            valleyService = zero(valleyService);
        }

        private static BigDecimal zero(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}