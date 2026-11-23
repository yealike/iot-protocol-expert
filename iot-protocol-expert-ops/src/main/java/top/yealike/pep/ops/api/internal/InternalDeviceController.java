package top.yealike.pep.ops.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.internal.dto.DevicePatch;
import top.yealike.pep.ops.api.internal.dto.DeviceView;
import top.yealike.pep.ops.api.internal.dto.LoginRequest;
import top.yealike.pep.ops.audit.service.AuditService;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DeviceRejectedException;
import top.yealike.pep.ops.devices.service.DeviceService;

@RestController
@RequestMapping(path = "/internal/devices", produces = "application/json")
@Tag(name = "内部设备", description = "协议网关内部使用的设备登录、解析与状态同步接口")
@SecurityRequirement(name = "opsServiceToken")
public class InternalDeviceController {

    private final DeviceService devices;
    private final AuditService audits;

    public InternalDeviceController(DeviceService devices, AuditService audits) {
        this.devices = devices;
        this.audits = audits;
    }

    @PostMapping("/login")
    @Operation(operationId = "internalDeviceLogin", summary = "设备登录登记")
    public DeviceView login(@RequestBody LoginRequest request) {
        DeviceInstance device = devices.login(request);
        audits.record(
                "protocol-channel",
                "device.login",
                "device",
                String.valueOf(device.id),
                "SUCCESS",
                device.protocol + "/" + device.deviceCode);
        return devices.view(device);
    }

    @GetMapping("/resolve")
    @Operation(operationId = "internalResolveDevice", summary = "按协议侧编号解析设备")
    public DeviceView resolve(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId) {
        return devices.view(devices.resolve(protocol, protocolDeviceId));
    }

    @PostMapping("/touch")
    @Operation(operationId = "internalTouchDevice", summary = "刷新设备在线时间")
    public void touch(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId) {
        devices.touch(protocol, protocolDeviceId);
    }

    @PostMapping("/offline")
    @Operation(operationId = "internalOfflineDevice", summary = "标记设备离线")
    public void offline(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @Parameter(description = "协议会话标识") @RequestParam(required = false) String sessionId) {
        devices.markOffline(protocol, protocolDeviceId, sessionId);
    }

    @PatchMapping("/patch")
    @Operation(operationId = "internalPatchDevice", summary = "更新设备运行属性")
    public DeviceView patch(
            @Parameter(description = "协议编码") @RequestParam String protocol,
            @Parameter(description = "协议侧设备编号") @RequestParam String protocolDeviceId,
            @RequestBody DevicePatch patch) {
        return devices.view(devices.patch(protocol, protocolDeviceId, patch));
    }

    @ExceptionHandler(DeviceRejectedException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> rejected(DeviceRejectedException e) {
        return org.springframework.http.ResponseEntity.status(409)
                .body(Map.of("error", "DEVICE_REJECTED", "message", e.getMessage()));
    }
}
