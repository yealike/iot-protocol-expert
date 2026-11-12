package top.yealike.ipe.ops.api.internal;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.service.AuditService;
import top.yealike.ipe.ops.service.DeviceService;

@RestController
@RequestMapping("/internal/devices")
public class InternalDeviceController {

    private final DeviceService devices;
    private final AuditService audits;

    public InternalDeviceController(DeviceService devices, AuditService audits) {
        this.devices = devices;
        this.audits = audits;
    }

    @PostMapping("/login")
    public OpsDtos.DeviceView login(@RequestBody OpsDtos.LoginRequest request) {
        DeviceInstance device = devices.login(request);
        audits.record(
                "protocol-channel",
                "device.login",
                "device",
                String.valueOf(device.id),
                "SUCCESS",
                device.protocol + "/" + device.protocolDeviceId);
        return devices.view(device);
    }

    @GetMapping("/resolve")
    public OpsDtos.DeviceView resolve(@RequestParam String protocol, @RequestParam String protocolDeviceId) {
        return devices.view(devices.resolve(protocol, protocolDeviceId));
    }

    @PostMapping("/touch")
    public void touch(@RequestParam String protocol, @RequestParam String protocolDeviceId) {
        devices.touch(protocol, protocolDeviceId);
    }

    @PostMapping("/offline")
    public void offline(
            @RequestParam String protocol,
            @RequestParam String protocolDeviceId,
            @RequestParam(required = false) String sessionId) {
        devices.markOffline(protocol, protocolDeviceId, sessionId);
    }

    @PatchMapping("/patch")
    public OpsDtos.DeviceView patch(
            @RequestParam String protocol,
            @RequestParam String protocolDeviceId,
            @RequestBody OpsDtos.DevicePatch patch) {
        return devices.view(devices.patch(protocol, protocolDeviceId, patch));
    }

    @ExceptionHandler(DeviceService.DeviceRejectedException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> rejected(
            DeviceService.DeviceRejectedException e) {
        return org.springframework.http.ResponseEntity.status(409)
                .body(Map.of("error", "DEVICE_REJECTED", "message", e.getMessage()));
    }
}
