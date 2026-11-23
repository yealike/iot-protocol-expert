package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.charging.dto.RateModelView;
import top.yealike.pep.ops.charging.service.RateModelService;

@RestController
@RequestMapping(path = "/api/ops/rate-models", produces = "application/json")
@Tag(name = "费率管理", description = "充电费率模型查询、维护与生效")
@SecurityRequirement(name = "sessionCookie")
public class RateModelController {

    private final RateModelService rates;

    public RateModelController(RateModelService rates) {
        this.rates = rates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ops:read')")
    @Operation(operationId = "listRateModels", summary = "查询费率模型")
    public List<RateModelView> list() {
        return rates.list().stream().map(rates::view).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ops:manage')")
    @Operation(operationId = "saveRateModel", summary = "新增或更新费率模型")
    public RateModelView save(@RequestBody RateModelView request) {
        return rates.view(rates.save(request));
    }
}
