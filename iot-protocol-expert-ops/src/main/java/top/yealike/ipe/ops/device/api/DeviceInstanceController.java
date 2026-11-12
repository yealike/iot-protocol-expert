package top.yealike.ipe.ops.device.api;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.common.PageResult;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.entity.DevicePropertyLatest;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.device.service.DevicePropertyService;
import top.yealike.ipe.ops.device.service.ThingModelResolver;
import top.yealike.ipe.ops.service.AuditService;
import top.yealike.ipe.ops.thingmodel.ThingModelCodec;

/** 设备实例：档案、生命周期、实时数据。 */
@RestController
@RequestMapping("/api/ops/devices")
public class DeviceInstanceController {

    private final DeviceInstanceService devices;
    private final DevicePropertyService properties;
    private final ThingModelResolver models;
    private final AuditService audits;

    public DeviceInstanceController(
            DeviceInstanceService devices,
            DevicePropertyService properties,
            ThingModelResolver models,
            AuditService audits) {
        this.devices = devices;
        this.properties = properties;
        this.models = models;
        this.audits = audits;
    }

    // ------------------------------------------------------------------ 查询

    @PostMapping("/query")
    public PageResult<DeviceDtos.DeviceRow> query(@RequestBody DeviceDtos.DeviceQuery query) {
        return devices.query(query);
    }

    /** 兼容简单的 GET 查询（模拟器、脚本调试用）。 */
    @GetMapping
    public PageResult<DeviceDtos.DeviceRow> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) List<String> states,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return devices.query(new DeviceDtos.DeviceQuery(
                keyword, deviceType, states, stationId, null, enabled, null, page, size, sort));
    }

    @GetMapping("/{id}")
    public DeviceDtos.DeviceDetail detail(@PathVariable Long id) {
        return devices.detail(id);
    }

    @GetMapping("/overview")
    public DeviceDtos.Overview overview() {
        return devices.overview();
    }

    @GetMapping("/{id}/model")
    public Map<String, Object> model(@PathVariable Long id) {
        return ThingModelCodec.toMap(models.of(id));
    }

    // ------------------------------------------------------------------ 写入

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.DeviceRow create(@RequestBody DeviceDtos.DeviceSave input, Principal principal) {
        DeviceInstance saved = devices.save(input);
        audits.record(
                name(principal), "device.create", "device", String.valueOf(saved.id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.DeviceRow update(
            @PathVariable Long id, @RequestBody DeviceDtos.DeviceSave input, Principal principal) {
        DeviceInstance existing = devices.get(id);
        DeviceDtos.DeviceSave merged = new DeviceDtos.DeviceSave(
                existing.deviceCode,
                input.name(),
                input.productId() == null ? existing.productId : input.productId(),
                input.protocolDeviceId(),
                input.stationId(),
                input.parentId(),
                input.enabled(),
                input.allowSimulate(),
                input.allowWork(),
                input.remark(),
                input.sceneAnchor(),
                input.tags(),
                input.channels(),
                input.configuration());
        DeviceInstance saved = devices.save(merged);
        audits.record(name(principal), "device.update", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        devices.delete(id);
        audits.record(name(principal), "device.delete", "device", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.DeviceRow deploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.deploy(id);
        audits.record(name(principal), "device.deploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/undeploy")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.DeviceRow undeploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.undeploy(id);
        audits.record(name(principal), "device.undeploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.DeviceRow enabled(@PathVariable Long id, @RequestParam boolean value, Principal principal) {
        DeviceInstance saved = devices.setEnabled(id, value);
        audits.record(name(principal), "device.enabled", "device", String.valueOf(id), "SUCCESS", "value=" + value);
        return devices.view(saved);
    }

    @PostMapping("/{id}/move")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.DeviceRow move(
            @PathVariable Long id, @RequestParam(required = false) Long stationId, Principal principal) {
        DeviceInstance saved = devices.move(id, stationId);
        audits.record(name(principal), "device.move", "device", String.valueOf(id), "SUCCESS", "station=" + stationId);
        return devices.view(saved);
    }

    @PostMapping("/{id}/work-allowance")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public DeviceDtos.DeviceRow workAllowance(
            @PathVariable Long id,
            @RequestParam boolean allowWork,
            @RequestParam(required = false) Integer maxPowerPercent,
            Principal principal) {
        DeviceInstance saved = devices.setAllowWork(id, allowWork, maxPowerPercent);
        audits.record(
                name(principal),
                "device.work-allowance",
                "device",
                String.valueOf(id),
                "SUCCESS",
                "allowWork=" + allowWork + ",maxPowerPercent=" + maxPowerPercent);
        return devices.view(saved);
    }

    // ------------------------------------------------------------------ 数据

    @GetMapping("/{id}/properties")
    public List<DeviceDtos.PropertyValue> properties(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        return properties.propertyViews(device, models.of(device));
    }

    @GetMapping("/{id}/channels")
    public List<DeviceDtos.ChannelView> channels(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        return properties.channelViews(device, models.of(device));
    }

    @GetMapping("/{id}/properties/{propertyId}/series")
    public DeviceDtos.SeriesView series(
            @PathVariable Long id,
            @PathVariable String propertyId,
            @RequestParam(required = false) String channelNo,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        return properties.series(id, channelNo, propertyId, from, to);
    }

    /** 手工写入属性（联调用）。生产环境由协议通道调用 /internal/**。 */
    @PostMapping("/{id}/properties")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public Map<String, Object> write(
            @PathVariable Long id, @RequestBody DeviceDtos.PropertyWrite input, Principal principal) {
        DevicePropertyLatest row = properties.write(id, input.channelNo(), input.propertyId(), input.value());
        audits.record(
                name(principal),
                "device.property.write",
                "device",
                String.valueOf(id),
                "SUCCESS",
                input.propertyId() + "=" + input.value());
        return Map.of(
                "propertyId",
                row.propertyId,
                "channelNo",
                row.channelNo,
                "valueType",
                row.valueType,
                "updatedAt",
                String.valueOf(row.updatedAt));
    }

    @GetMapping("/{id}/events")
    public List<DeviceDtos.EventView> events(@PathVariable Long id) {
        return devices.detail(id).events();
    }

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
