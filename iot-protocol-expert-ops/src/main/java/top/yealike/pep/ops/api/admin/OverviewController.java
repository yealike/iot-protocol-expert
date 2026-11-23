package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.devices.dto.Overview;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;

/** 运维总览：充电桩与订单业务统计。 */
@RestController
@RequestMapping(path = "/api/ops/overview", produces = "application/json")
@Tag(name = "运营总览", description = "首页使用的充电桩和订单统计")
@SecurityRequirement(name = "sessionCookie")
public class OverviewController {

    private final DeviceInstanceService devices;
    private final ChargeOrderMapper orders;

    public OverviewController(DeviceInstanceService devices, ChargeOrderMapper orders) {
        this.orders = orders;
        this.devices = devices;
    }

    @GetMapping
    @Operation(operationId = "getOverview", summary = "查询充电桩运营总览")
    public Overview overview() {
        return devices.overview();
    }

    @GetMapping("/business")
    @Operation(operationId = "getBusinessOverview", summary = "查询今日业务统计")
    public Map<String, Object> business() {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var result = orders.summarizeAll(
                top.yealike.pep.ops.charging.domain.ChargeOrder.Status.SETTLED,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date", today.toString());
        response.put("timezone", "Asia/Shanghai");
        response.put("settledOrderCount", result.getC());
        response.put("settledEnergy", result.getE());
        response.put("settledAmount", result.getA());
        response.put(
                "chargedEnergy", orders.sumEnergyActiveBetween(today.plusDays(1).atStartOfDay(), today.atStartOfDay()));
        response.put("dischargedEnergy", java.math.BigDecimal.ZERO);
        List<Map<String, Object>> daily = new ArrayList<>();
        for (int days = 6; days >= 0; days--) {
            var date = today.minusDays(days);
            var day = orders.summarizeAll(
                    top.yealike.pep.ops.charging.domain.ChargeOrder.Status.SETTLED,
                    date.atStartOfDay(),
                    date.plusDays(1).atStartOfDay());
            daily.add(
                    Map.of("date", date.toString(), "energy", day.getE(), "amount", day.getA(), "orders", day.getC()));
        }
        response.put("daily", daily);
        response.put(
                "activeOrderCount",
                orders.countByStatusIn(java.util.List.of(
                        top.yealike.pep.ops.charging.domain.ChargeOrder.Status.PENDING,
                        top.yealike.pep.ops.charging.domain.ChargeOrder.Status.CHARGING)));
        return response;
    }
}
