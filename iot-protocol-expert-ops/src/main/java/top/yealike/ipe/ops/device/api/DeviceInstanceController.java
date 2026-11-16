package top.yealike.ipe.ops.device.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "设备管理", description = "设备档案、部署、运行权限和实时数据")
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
    @Operation(summary = "分页查询设备", description = "支持关键字、设备类型、状态、站点和标签筛选")
    public PageResult<DeviceDtos.DeviceRow> query(@RequestBody DeviceDtos.DeviceQuery query) {
        return devices.query(query);
    }

    /** 兼容简单的 GET 查询（模拟器、脚本调试用）。 */
    @GetMapping
    @Operation(summary = "简要查询设备列表")
    public PageResult<DeviceDtos.DeviceRow> list(
            @Parameter(description = "设备名称或编码关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "设备类型编码") @RequestParam(required = false) String deviceType,
            @Parameter(description = "设备状态列表") @RequestParam(required = false) List<String> states,
            @Parameter(description = "所属站点 ID") @RequestParam(required = false) Long stationId,
            @Parameter(description = "是否启用") @RequestParam(required = false) Boolean enabled,
            @Parameter(description = "从 0 开始的页码") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页记录数") @RequestParam(required = false) Integer size,
            @Parameter(description = "排序字段；仅支持服务端白名单") @RequestParam(required = false) String sort) {
        return devices.query(new DeviceDtos.DeviceQuery(
                keyword, deviceType, states, stationId, null, enabled, null, page, size, sort));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询设备详情", description = "包含物模型、通道、属性、事件、告警和命令")
    public DeviceDtos.DeviceDetail detail(@Parameter(description = "设备 ID") @PathVariable Long id) {
        return devices.detail(id);
    }

    @GetMapping("/overview")
    @Operation(summary = "查询设备统计总览")
    public DeviceDtos.Overview overview() {
        return devices.overview();
    }

    @GetMapping("/{id}/model")
    public Map<String, Object> model(@PathVariable Long id) {
        return ThingModelCodec.toMap(models.of(id));
    }

    // ------------------------------------------------------------------ 写入

    @PostMapping
    @Operation(summary = "创建设备")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow create(@RequestBody DeviceDtos.DeviceSave input, Principal principal) {
        if (input.deviceCode() != null
                && devices.findByCode(input.deviceCode().trim()).isPresent()) {
            throw new IllegalArgumentException("设备编码已存在: " + input.deviceCode().trim());
        }
        DeviceInstance saved = devices.save(input);
        audits.record(
                name(principal), "device.create", "device", String.valueOf(saved.id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新设备")
    @PreAuthorize("hasAuthority('ops:manage')")
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
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        devices.delete(id);
        audits.record(name(principal), "device.delete", "device", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/deploy")
    @Operation(summary = "部署设备")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow deploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.deploy(id);
        audits.record(name(principal), "device.deploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/undeploy")
    @Operation(summary = "取消部署设备")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow undeploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.undeploy(id);
        audits.record(name(principal), "device.undeploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/enabled")
    @Operation(summary = "设置设备启用状态")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow enabled(@PathVariable Long id, @RequestParam boolean value, Principal principal) {
        DeviceInstance saved = devices.setEnabled(id, value);
        audits.record(name(principal), "device.enabled", "device", String.valueOf(id), "SUCCESS", "value=" + value);
        return devices.view(saved);
    }

    @PostMapping("/{id}/move")
    @Operation(summary = "调整设备所属站点")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow move(
            @PathVariable Long id, @RequestParam(required = false) Long stationId, Principal principal) {
        DeviceInstance saved = devices.move(id, stationId);
        audits.record(name(principal), "device.move", "device", String.valueOf(id), "SUCCESS", "station=" + stationId);
        return devices.view(saved);
    }

    @PostMapping("/{id}/work-allowance")
    @Operation(summary = "设置设备运行许可")
    @PreAuthorize("hasAuthority('ops:operate')")
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
    @Operation(summary = "查询设备属性")
    public List<DeviceDtos.PropertyValue> properties(@Parameter(description = "设备 ID") @PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        return properties.propertyViews(device, models.of(device));
    }

    @GetMapping("/{id}/channels")
    @Operation(summary = "查询设备通道")
    public List<DeviceDtos.ChannelView> channels(@Parameter(description = "设备 ID") @PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        return properties.channelViews(device, models.of(device));
    }

    @GetMapping("/{id}/properties/{propertyId}/series")
    @Operation(summary = "查询设备属性时序")
    public DeviceDtos.SeriesView series(
            @Parameter(description = "设备 ID") @PathVariable Long id,
            @Parameter(description = "物模型属性标识") @PathVariable String propertyId,
            @Parameter(description = "通道编号") @RequestParam(required = false) String channelNo,
            @Parameter(description = "查询开始时间，ISO 日期时间") @RequestParam(required = false) LocalDateTime from,
            @Parameter(description = "查询结束时间，ISO 日期时间") @RequestParam(required = false) LocalDateTime to) {
        return properties.series(id, channelNo, propertyId, from, to);
    }

    /** 手工写入属性（联调用）。生产环境由协议通道调用 /internal/**。 */
    @PostMapping("/{id}/properties")
    @PreAuthorize("hasAuthority('ops:operate')")
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
