package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.charging.service.RateModelService;

@RestController
@RequestMapping("/api/ops/rate-models")
@Tag(name = "费率管理", description = "充电费率模型查询、维护与生效")
public class RateModelController {

    private final RateModelService rates;

    public RateModelController(RateModelService rates) {
        this.rates = rates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ops:read')")
    @Operation(summary = "查询费率模型")
    public List<OpsDtos.RateModelView> list() {
        return rates.list().stream().map(rates::view).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ops:manage')")
    @Operation(summary = "新增或更新费率模型")
    public OpsDtos.RateModelView save(@RequestBody OpsDtos.RateModelView request) {
        return rates.view(rates.save(request));
    }

    @PostMapping("/{modelNo}/activate")
    @PreAuthorize("hasAuthority('ops:manage')")
    @Operation(summary = "激活费率模型")
    public OpsDtos.RateModelView activate(@PathVariable String modelNo) {
        return rates.view(rates.activate(modelNo));
    }
}
