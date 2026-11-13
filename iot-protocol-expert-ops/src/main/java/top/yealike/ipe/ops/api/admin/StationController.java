package top.yealike.ipe.ops.api.admin;

import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.domain.Station;
import top.yealike.ipe.ops.service.AuditService;
import top.yealike.ipe.ops.service.DeviceService;
import top.yealike.ipe.ops.service.StationService;

@RestController
@RequestMapping("/api/ops/stations")
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
    public List<Station> list() {
        return stations.list();
    }

    @GetMapping("/{id}")
    public Station get(@PathVariable Long id) {
        return stations.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ops:manage')")
    public Station save(@RequestBody Station station, Principal principal) {
        Station saved = stations.save(station);
        audits.record(principal.getName(), "station.save", "station", String.valueOf(saved.id), "SUCCESS", saved.name);
        return saved;
    }

    @GetMapping("/{id}/summary")
    public StationService.StationSummary summary(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return stations.summary(id, from, to);
    }

    @GetMapping("/{id}/devices")
    public List<Map<String, Object>> devices(@PathVariable Long id, @RequestParam(required = false) String protocol) {
        return stations.devicesOf(id, protocol).stream().map(this::deviceRow).toList();
    }

    @PostMapping("/{id}/devices/{deviceId}")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> bind(@PathVariable Long id, @PathVariable Long deviceId, Principal principal) {
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
