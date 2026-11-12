package top.yealike.ipe.simulator.api;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.simulator.config.SimulatorProperties;
import top.yealike.ipe.simulator.runtime.SimulatorRuntime;

/** 模拟器控制接口。 */
@RestController
@RequestMapping("/api")
public class SimulatorController {

    private final SimulatorRuntime runtime;
    private final SimulatorProperties properties;

    public SimulatorController(SimulatorRuntime runtime, SimulatorProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return runtime.state();
    }

    /** 园区 3D 沙盘地址：由独立前端站点托管，模拟器只负责内嵌。 */
    @GetMapping("/campus-url")
    public Map<String, Object> campusUrl() {
        String base = properties.getWebBaseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return Map.of("url", normalized + "/campus");
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return runtime.configuration();
    }

    @GetMapping("/protocols")
    public Object protocols() {
        return runtime.state().get("protocols");
    }

    @GetMapping("/devices")
    public Object devices() {
        return runtime.devices();
    }

    public record DeviceSelectRequest(Long deviceId) {}

    @PostMapping("/device/select")
    public Map<String, Object> selectDevice(@RequestBody DeviceSelectRequest request) {
        return Map.of("message", "已选择设备 " + request.deviceId(), "state", runtime.selectDevice(request.deviceId()));
    }

    public record ProtocolRequest(String protocol) {}

    @PostMapping("/protocol")
    public Object protocol(@RequestBody ProtocolRequest request) {
        throw new IllegalArgumentException("协议由设备档案决定，请从设备列表重新选桩");
    }

    @PostMapping("/connect")
    public Map<String, Object> connect() {
        return Map.of("connected", runtime.connect(), "state", runtime.state());
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect() {
        runtime.disconnect();
        return Map.of("connected", false);
    }

    @PostMapping("/guns/{gunNo}/plug")
    public Map<String, Object> plug(@PathVariable String gunNo) {
        return Map.of("message", runtime.plug(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/unplug")
    public Map<String, Object> unplug(@PathVariable String gunNo) {
        return Map.of("message", runtime.unplug(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/start")
    public Map<String, Object> start(@PathVariable String gunNo) {
        return Map.of("message", runtime.startByCard(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/stop")
    public Map<String, Object> stop(@PathVariable String gunNo) {
        return Map.of("message", runtime.stopLocal(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/fault")
    public Map<String, Object> fault(@PathVariable String gunNo, @RequestParam(defaultValue = "true") boolean enable) {
        return Map.of("message", runtime.fault(gunNo, enable), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/report")
    public Map<String, Object> report(@PathVariable String gunNo) {
        return Map.of("message", runtime.reportRealtime(gunNo), "state", runtime.state());
    }

    @GetMapping("/stop-reasons")
    public Object stopReasons() {
        return top.yealike.ipe.protocol.codec.StopReasons.ALL;
    }

    @PostMapping("/guns/{gunNo}/terminate")
    public Object terminate(@PathVariable String gunNo, @RequestParam int reason) {
        return Map.of("message", runtime.terminate(gunNo, reason));
    }

    public record ChargeLimits(double energy, double amount, int minutes) {}

    @PostMapping("/guns/{gunNo}/limits")
    public Object limits(
            @PathVariable String gunNo, @org.springframework.web.bind.annotation.RequestBody ChargeLimits r) {
        runtime.chargeLimits(gunNo, r.energy(), r.amount(), r.minutes());
        return runtime.state();
    }

    public record UpgradeScenario(int result) {}

    @PostMapping("/upgrade-scenario")
    public Object upgradeScenario(@org.springframework.web.bind.annotation.RequestBody UpgradeScenario r) {
        runtime.upgradeScenario(r.result());
        return Map.of("result", r.result());
    }

    public record ClockRequest(LocalDateTime time, Double speed, Integer advanceSeconds) {}

    @PostMapping("/clock")
    public Object clock(@org.springframework.web.bind.annotation.RequestBody ClockRequest request) {
        return runtime.configureClock(request.time(), request.speed(), request.advanceSeconds());
    }

    public record VehicleRequest(double capacityKwh, int soc, String vin, String physicalCard) {}

    @PostMapping("/guns/{gunNo}/vehicle")
    public Object vehicle(
            @PathVariable String gunNo, @org.springframework.web.bind.annotation.RequestBody VehicleRequest r) {
        runtime.configureVehicle(gunNo, r.capacityKwh(), r.soc(), r.vin(), r.physicalCard());
        return runtime.state();
    }

    @PostMapping("/guns/{gunNo}/offline-start")
    public Object offline(@PathVariable String gunNo) {
        return Map.of("message", runtime.startOffline(gunNo));
    }

    @PostMapping("/guns/{gunNo}/vin-start")
    public Object vin(@PathVariable String gunNo) {
        return Map.of("message", runtime.startVin(gunNo));
    }

    @PostMapping("/parallel-start")
    public Object parallel() {
        return Map.of("message", runtime.startParallel());
    }

    @GetMapping("/logs")
    public List<SimulatorRuntime.FrameLog> logs(@RequestParam(defaultValue = "100") int limit) {
        return runtime.logs(limit);
    }

    @GetMapping("/frames")
    public List<SimulatorRuntime.FrameLog> frames(@RequestParam(defaultValue = "100") int limit) {
        return runtime.logs(limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_ARGUMENT");
        body.put("message", e.getMessage());
        body.put("time", LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
