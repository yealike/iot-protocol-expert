package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.audit.service.AuditService;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DevicePropertyLatest;
import top.yealike.pep.ops.devices.dto.DeviceDetail;
import top.yealike.pep.ops.devices.dto.DeviceQuery;
import top.yealike.pep.ops.devices.dto.DeviceRow;
import top.yealike.pep.ops.devices.dto.DeviceSave;
import top.yealike.pep.ops.devices.dto.PropertyWrite;
import top.yealike.pep.ops.devices.dto.ProtocolMappingsSave;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;
import top.yealike.pep.ops.devices.service.DevicePropertyService;
import top.yealike.pep.ops.support.page.PageResult;

/** 充电桩档案、枪口实时数据和生命周期。 */
@RestController
@RequestMapping(path = "/api/ops/devices", produces = "application/json")
@Tag(name = "充电桩管理", description = "设备 SN、产品协议、枪口实时数据和第三方映射")
@SecurityRequirement(name = "sessionCookie")
public class DeviceInstanceController {

    private final DeviceInstanceService devices;
    private final DevicePropertyService properties;
    private final AuditService audits;

    public DeviceInstanceController(
            DeviceInstanceService devices, DevicePropertyService properties, AuditService audits) {
        this.devices = devices;
        this.properties = properties;
        this.audits = audits;
    }

    @PostMapping("/query")
    @Operation(operationId = "queryDevices", summary = "分页查询充电桩", description = "8 位输入按设备 SN 精确查询，10 位输入按平台枪号查询")
    public PageResult<DeviceRow> query(@RequestBody DeviceQuery query) {
        return devices.query(query);
    }

    @GetMapping("/lookup")
    @Operation(operationId = "lookupDevice", summary = "按设备 SN 或平台枪号查询基本信息")
    public DeviceRow lookup(@RequestParam String sn) {
        PageResult<DeviceRow> result = devices.query(new DeviceQuery(sn, null, null, null, 0, 1, null));
        if (result.content().isEmpty()) {
            throw new IllegalArgumentException("充电桩不存在: " + sn);
        }
        return result.content().get(0);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getDevice", summary = "查询充电桩详情")
    public DeviceDetail detail(@PathVariable Long id) {
        return devices.detail(id);
    }

    @PostMapping
    @Operation(operationId = "createDevice", summary = "创建充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceRow create(@RequestBody DeviceSave input, Principal principal) {
        DeviceInstance saved = devices.create(input);
        audits.record(
                name(principal), "device.create", "device", String.valueOf(saved.id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateDevice", summary = "更新充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceRow update(@PathVariable Long id, @RequestBody DeviceSave input, Principal principal) {
        DeviceInstance saved = devices.update(id, input);
        audits.record(name(principal), "device.update", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PutMapping("/{id}/protocol-mappings")
    @Operation(operationId = "saveDeviceProtocolMappings", summary = "保存第三方协议映射")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDetail saveProtocolMappings(
            @PathVariable Long id, @RequestBody ProtocolMappingsSave input, Principal principal) {
        DeviceInstance saved = devices.saveMappings(id, input == null ? java.util.List.of() : input.mappings());
        audits.record(
                name(principal), "device.mapping.update", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.detail(id);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteDevice", summary = "删除充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        devices.delete(id);
        audits.record(name(principal), "device.delete", "device", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/deploy")
    @Operation(operationId = "deployDevice", summary = "部署充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceRow deploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.deploy(id);
        audits.record(name(principal), "device.deploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/undeploy")
    @Operation(operationId = "undeployDevice", summary = "取消部署充电桩")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceRow undeploy(@PathVariable Long id, Principal principal) {
        DeviceInstance saved = devices.undeploy(id);
        audits.record(name(principal), "device.undeploy", "device", String.valueOf(id), "SUCCESS", saved.deviceCode);
        return devices.view(saved);
    }

    @PostMapping("/{id}/enabled")
    @Operation(operationId = "setDeviceEnabled", summary = "设置充电桩启用状态")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceRow enabled(@PathVariable Long id, @RequestParam boolean value, Principal principal) {
        DeviceInstance saved = devices.setEnabled(id, value);
        audits.record(name(principal), "device.enabled", "device", String.valueOf(id), "SUCCESS", "value=" + value);
        return devices.view(saved);
    }

    @PostMapping("/{id}/work-allowance")
    @Operation(operationId = "setDeviceWorkAllowance", summary = "设置充电桩运行许可")
    @PreAuthorize("hasAuthority('ops:operate')")
    public DeviceRow workAllowance(
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

    @PostMapping("/{id}/properties")
    @Operation(operationId = "writeDeviceProperty", summary = "写入设备属性")
    @PreAuthorize("hasAuthority('ops:operate')")
    public Map<String, Object> write(@PathVariable Long id, @RequestBody PropertyWrite input, Principal principal) {
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

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
