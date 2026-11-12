package top.yealike.ipe.ops.api.admin;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.service.RateModelService;

/** 模拟器读取与保存平台费率模型；设备下发仍走命令通道。 */
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

    @PostMapping
    public OpsDtos.RateModelView save(@RequestBody OpsDtos.RateModelView request) {
        return rates.view(rates.save(request));
    }
}
