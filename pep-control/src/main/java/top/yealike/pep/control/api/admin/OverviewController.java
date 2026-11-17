package top.yealike.pep.control.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.control.charging.service.StationService;
import top.yealike.pep.control.devices.dto.DeviceDtos;
import top.yealike.pep.control.devices.service.DeviceInstanceService;
import top.yealike.pep.control.devices.thingmodel.DeviceTypeCatalog;

/** 运维总览：首页与 3D 大屏共用。 */
@RestController
@RequestMapping("/api/ops/overview")
@Tag(name = "运营总览", description = "首页与大屏使用的设备、订单和站点统计")
public class OverviewController {

    private final DeviceInstanceService devices;
    private final StationService stations;
    private final ChargeOrderMapper orders;

    public OverviewController(DeviceInstanceService devices, StationService stations, ChargeOrderMapper orders) {
        this.orders = orders;
        this.devices = devices;
        this.stations = stations;
    }

    @GetMapping
    @Operation(summary = "查询设备运营总览")
    public DeviceDtos.Overview overview() {
        return devices.overview();
    }

    @GetMapping("/business")
    @Operation(summary = "查询今日业务统计")
    public Map<String, Object> business() {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var result = orders.summarizeAll(
                top.yealike.pep.control.charging.domain.ChargeOrder.Status.SETTLED,
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
                    top.yealike.pep.control.charging.domain.ChargeOrder.Status.SETTLED,
                    date.atStartOfDay(),
                    date.plusDays(1).atStartOfDay());
            daily.add(
                    Map.of("date", date.toString(), "energy", day.getE(), "amount", day.getA(), "orders", day.getC()));
        }
        response.put("daily", daily);
        response.put(
                "activeOrderCount",
                orders.countByStatusIn(java.util.List.of(
                        top.yealike.pep.control.charging.domain.ChargeOrder.Status.PENDING,
                        top.yealike.pep.control.charging.domain.ChargeOrder.Status.CHARGING)));
        return response;
    }

    /** 设备类型目录 + 在线/告警计数，供导航与筛选器使用。 */
    @GetMapping("/device-types")
    @Operation(summary = "查询设备类型统计")
    public List<Map<String, Object>> deviceTypes() {
        Map<String, DeviceDtos.DeviceTypeSummary> summary = new LinkedHashMap<>();
        for (DeviceDtos.DeviceTypeSummary item : devices.overview().byType()) {
            summary.put(item.deviceType(), item);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeviceTypeCatalog type : DeviceTypeCatalog.values()) {
            Map<String, Object> item = new LinkedHashMap<>(type.describe());
            DeviceDtos.DeviceTypeSummary value = summary.get(type.name());
            item.put("total", value == null ? 0 : value.total());
            item.put("online", value == null ? 0 : value.online());
            item.put("fault", value == null ? 0 : value.fault());
            item.put("offline", value == null ? 0 : value.offline());
            result.add(item);
        }
        return result;
    }

    @GetMapping("/stations")
    @Operation(summary = "查询站点数量")
    public long stationCount() {
        return stations.list().size();
    }
}
