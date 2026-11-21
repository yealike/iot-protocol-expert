package top.yealike.pep.ops.api.admin;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.charging.service.RateModelService;

/** 模拟器只读兼容入口；费率管理统一走 /api/ops/rate-models。 */
@RestController
@RequestMapping("/api/ops/simulator/rate-models")
public class SimulatorRateModelController {

    private final RateModelService rates;

    public SimulatorRateModelController(RateModelService rates) {
        this.rates = rates;
    }

    @GetMapping
    public List<OpsDtos.RateModelView> list() {
        return rates.list().stream().map(rates::view).toList();
    }
}
