package top.yealike.pep.control.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.control.devices.entity.DeviceInstance;
import top.yealike.pep.control.devices.service.DeviceInstanceService;
import top.yealike.pep.control.charging.domain.Station;
import top.yealike.pep.control.audit.AuditService;
import top.yealike.pep.control.devices.service.DeviceService;
import top.yealike.pep.control.charging.service.StationService;

@RestController
@RequestMapping("/api/ops/stations")
@Tag(name = "站点管理", description = "站点档案、设备绑定和运营汇总")
public class StationController {

    private final StationService stations;
    private final DeviceInstanceService instances;
    private final DeviceService devices;
    private final AuditService audits;

    public StationController(
            StationService stations, DeviceInstanceService instances, DeviceService devices, AuditService audits) {
        this.stations = stations;
        this.instances = instances;
        this.devices = devices;
        this.audits = audits;
    }

    @GetMapping
    @Operation(summary = "查询站点列表")
    public List<Station> list() {
        return stations.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询站点详情")
    public Station get(@Parameter(description = "站点 ID") @PathVariable Long id) {
        return stations.get(id);
    }

    @PostMapping
    @Operation(summary = "创建或更新站点")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Station save(@RequestBody Station station, Principal principal) {
        Station saved = stations.save(station);
        audits.record(principal.getName(), "station.save", "station", String.valueOf(saved.id), "SUCCESS", saved.name);
        return saved;
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "查询站点运营汇总")
    public StationService.StationSummary summary(
            @Parameter(description = "站点 ID") @PathVariable Long id,
            @Parameter(description = "统计开始日期，含当天") @RequestParam(required = false) LocalDate from,
            @Parameter(description = "统计结束日期，含当天") @RequestParam(required = false) LocalDate to) {
        return stations.summary(id, from, to);
    }

    @GetMapping("/{id}/devices")
    @Operation(summary = "查询站点设备")
    public List<Map<String, Object>> devices(
            @Parameter(description = "站点 ID") @PathVariable Long id,
            @Parameter(description = "按协议过滤") @RequestParam(required = false) String protocol) {
        return stations.devicesOf(id, protocol).stream().map(this::deviceRow).toList();
    }

    @PostMapping("/{id}/devices/{deviceId}")
    @PreAuthorize("hasAuthority('ops:manage')")
    @Operation(summary = "将设备绑定到站点")
    public Map<String, Object> bind(
            @Parameter(description = "站点 ID") @PathVariable Long id,
            @Parameter(description = "设备 ID") @PathVariable Long deviceId,
            Principal principal) {
        DeviceInstance device = instances.move(deviceId, id);
        audits.record(
                principal.getName(), "device.move", "device", String.valueOf(deviceId), "SUCCESS", "station=" + id);
        return deviceRow(device);
    }

    private Map<String, Object> deviceRow(DeviceInstance device) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", device.id);
        row.put("sn", device.deviceCode);
        row.put("protocol", device.protocol);
        row.put("protocolDeviceId", device.protocolDeviceId);
        row.put("gunCount", devices.gunCount(device.id));
        row.put("online", device.isOnline());
        row.put("enabled", device.enabled);
        row.put("lastSeen", device.lastSeen);
        return row;
    }
}
