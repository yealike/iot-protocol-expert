package top.yealike.pep.ops.api.simulator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.charging.dto.RateModelView;
import top.yealike.pep.ops.charging.service.RateModelService;

/** 模拟器只读兼容入口；费率管理统一走 /api/ops/rate-models。 */
@RestController
@RequestMapping(path = "/api/ops/simulator/rate-models", produces = "application/json")
@Tag(name = "模拟器费率", description = "供模拟器读取当前生效费率模型")
@SecurityRequirement(name = "simulatorToken")
public class SimulatorRateModelController {

    private final RateModelService rates;

    public SimulatorRateModelController(RateModelService rates) {
        this.rates = rates;
    }

    @GetMapping
    @Operation(operationId = "listSimulatorRateModels", summary = "查询模拟器可用的费率模型")
    public List<RateModelView> list() {
        return rates.list().stream().map(rates::view).toList();
    }
}
