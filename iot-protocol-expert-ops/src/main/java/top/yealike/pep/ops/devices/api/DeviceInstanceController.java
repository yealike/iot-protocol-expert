package top.yealike.pep.ops.devices.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.audit.AuditService;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DevicePropertyLatest;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;
import top.yealike.pep.ops.devices.service.DevicePropertyService;
import top.yealike.pep.ops.devices.service.ThingModelResolver;
import top.yealike.pep.ops.support.page.PageResult;

/** 充电桩档案、枪口实时数据和生命周期。 */
@RestController
@RequestMapping("/api/ops/devices")
@Tag(name = "充电桩管理", description = "设备 SN、产品协议、枪口实时数据和第三方映射")
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

    @PostMapping("/query")
    @Operation(summary = "分页查询充电桩", description = "8 位输入按设备 SN 精确查询，10 位输入按平台枪号查询")
    public PageResult<DeviceDtos.DeviceRow> query(@RequestBody DeviceDtos.DeviceQuery query) {
        return devices.query(query);
    }

    /** 兼容脚本和模拟器的简单查询。 */
    @GetMapping
    @Operation(summary = "简要查询充电桩列表")
    public PageResult<DeviceDtos.DeviceRow> list(
            @Parameter(description = "8 位设备 SN、10 位平台枪号或设备名称") @RequestParam(required = false) String keyword,
            @Parameter(description = "设备状态列表") @RequestParam(required = false) List<String> states,
            @Parameter(description = "所属产品 ID") @RequestParam(required = false) Long productId,
            @Parameter(description = "是否启用") @RequestParam(required = false) Boolean enabled,
            @Parameter(description = "从 0 开始的页码") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页记录数") @RequestParam(required = false) Integer size,
            @Parameter(description = "排序字段；仅支持服务端白名单") @RequestParam(required = false) String sort) {
        return devices.query(new DeviceDtos.DeviceQuery(keyword, states, productId, enabled, page, size, sort));
    }

    @GetMapping("/lookup")
    @Operation(summary = "按设备 SN 或平台枪号查询基本信息", description = "供运维页面、脚本和 REST-to-MCP 使用")
    public DeviceDtos.DeviceRow lookup(@RequestParam String sn) {
        PageResult<DeviceDtos.DeviceRow> result =
                devices.query(new DeviceDtos.DeviceQuery(sn, null, null, null, 0, 1, null));
        if (result.content().isEmpty()) {
            throw new IllegalArgumentException("充电桩不存在: " + sn);
        }
        return result.content().get(0);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询充电桩详情", description = "包含枪口实时数据、事件、指令和第三方协议映射")
    public DeviceDtos.DeviceDetail detail(@Parameter(description = "设备 ID") @PathVariable Long id) {
        return devices.detail(id);
    }

    @GetMapping("/overview")
    @Operation(summary = "查询充电桩统计总览")
    public DeviceDtos.Overview overview() {
        return devices.overview();
    }

    @PostMapping
    @Operation(summary = "创建充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow create(@RequestBody DeviceDtos.DeviceSave input, Principal principal) {
        DeviceInstance saved = devices.create(input);
        audits.record(
                name(principal), "device.create", "device", String.valueOf(saved.id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow update(
            @PathVariable Long id, @RequestBody DeviceDtos.DeviceSave input, Principal principal) {
        DeviceInstance saved = devices.update(id, input);
        audits.record(name(principal), "device.update", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PutMapping("/{id}/protocol-mappings")
    @Operation(summary = "保存第三方协议映射")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceDetail saveProtocolMappings(
            @PathVariable Long id, @RequestBody DeviceDtos.ProtocolMappingsSave input, Principal principal) {
        DeviceInstance saved = devices.saveMappings(id, input == null ? List.of() : input.mappings());
        audits.record(
                name(principal), "device.mapping.update", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.detail(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        devices.delete(id);
        audits.record(name(principal), "device.delete", "device", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/deploy")
    @Operation(summary = "部署充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow deploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.deploy(id);
        audits.record(name(principal), "device.deploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/undeploy")
    @Operation(summary = "取消部署充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow undeploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.undeploy(id);
        audits.record(name(principal), "device.undeploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/enabled")
    @Operation(summary = "设置充电桩启用状态")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.DeviceRow enabled(@PathVariable Long id, @RequestParam boolean value, Principal principal) {
        DeviceInstance saved = devices.setEnabled(id, value);
        audits.record(name(principal), "device.enabled", "device", String.valueOf(id), "SUCCESS", "value=" + value);
        return devices.view(saved);
    }

    @PostMapping("/{id}/work-allowance")
    @Operation(summary = "设置充电桩运行许可")
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

    @GetMapping("/{id}/properties")
    @Operation(summary = "查询充电桩非枪口属性")
    public List<DeviceDtos.PropertyValue> properties(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        return properties.propertyViews(device, models.of(device));
    }

    @GetMapping("/{id}/channels")
    @Operation(summary = "查询充电枪实时数据")
    public List<DeviceDtos.ChannelView> channels(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        return properties.channelViews(device, models.of(device));
    }

    @GetMapping("/{id}/properties/{propertyId}/series")
    @Operation(summary = "查询属性时序")
    public DeviceDtos.SeriesView series(
            @PathVariable Long id,
            @PathVariable String propertyId,
            @RequestParam(required = false) String gunNo,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        return properties.series(id, gunNo, propertyId, from, to);
    }

    @PostMapping("/{id}/properties")
    @PreAuthorize("hasAuthority('ops:operate')")
    public Map<String, Object> write(
            @PathVariable Long id, @RequestBody DeviceDtos.PropertyWrite input, Principal principal) {
        DevicePropertyLatest row = properties.write(id, input.gunNo(), input.propertyId(), input.value());
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
                "gunNo",
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
