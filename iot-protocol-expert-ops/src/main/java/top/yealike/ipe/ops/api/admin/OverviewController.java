package top.yealike.ipe.ops.api.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.service.StationService;
import top.yealike.ipe.ops.thingmodel.DeviceTypeCatalog;

/** 运维总览：首页与 3D 大屏共用。 */
@RestController
@RequestMapping("/api/ops/overview")
public class OverviewController {

    private final DeviceInstanceService devices;
    private final StationService stations;
    private final top.yealike.ipe.ops.repository.ChargeOrderRepository orders;

    public OverviewController(DeviceInstanceService devices, StationService stations, top.yealike.ipe.ops.repository.ChargeOrderRepository orders) {
        this.orders = orders;
        this.devices = devices;
        this.stations = stations;
    }

    @GetMapping
    public DeviceDtos.Overview overview() {
        return devices.overview();
    }

    @GetMapping("/business")
    public Map<String, Object> business() {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var result = orders.summarizeAll(top.yealike.ipe.ops.domain.ChargeOrder.Status.SETTLED,
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date", today.toString());
        response.put("timezone", "Asia/Shanghai");
        response.put("settledOrderCount", result.getC());
        response.put("settledEnergy", result.getE());
        response.put("settledAmount", result.getA());
        List<Map<String, Object>> daily = new ArrayList<>();
        for (int days = 6; days >= 0; days--) {
            var date = today.minusDays(days);
            var day = orders.summarizeAll(top.yealike.ipe.ops.domain.ChargeOrder.Status.SETTLED,
                    date.atStartOfDay(), date.plusDays(1).atStartOfDay());
            daily.add(Map.of("date", date.toString(), "energy", day.getE(), "amount", day.getA(), "orders", day.getC()));
        }
        response.put("daily", daily);
        response.put("activeOrderCount", orders.countByStatusIn(java.util.List.of(
                top.yealike.ipe.ops.domain.ChargeOrder.Status.PENDING,
                top.yealike.ipe.ops.domain.ChargeOrder.Status.CHARGING)));
        return response;
    }

    /** 设备类型目录 + 在线/告警计数，供导航与筛选器使用。 */
    @GetMapping("/device-types")
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
    public long stationCount() {
        return stations.list().size();
    }
}
