package top.yealike.pep.simulator.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import top.yealike.pep.simulator.runtime.ChargerFleet;
import top.yealike.pep.simulator.runtime.SimulatorRuntime;

/** 模拟器控制接口。 */
@RestController
@RequestMapping("/api")
@Tag(name = "充电模拟器", description = "设备选择、充电控制、实时上报和并发模拟")
public class SimulatorController {

    private final SimulatorRuntime runtime;
    private final ChargerFleet fleet;

    public SimulatorController(SimulatorRuntime runtime, ChargerFleet fleet) {
        this.runtime = runtime;
        this.fleet = fleet;
    }

    @GetMapping("/state")
    @Operation(summary = "查询模拟器当前状态")
    public Map<String, Object> state() {
        return runtime.state();
    }

    @GetMapping("/config")
    @Operation(summary = "查询模拟器配置")
    public Map<String, Object> config() {
        return runtime.configuration();
    }

    @GetMapping("/protocols")
    @Operation(summary = "查询可用协议")
    public Object protocols() {
        return runtime.state().get("protocols");
    }

    @GetMapping("/devices")
    @Operation(summary = "查询可模拟设备")
    public Object devices() {
        return runtime.devices();
    }

    public record DeviceSelectRequest(
            @Schema(description = "要在交互模拟器中使用的设备 ID") Long deviceId) {}

    @PostMapping("/device/select")
    @Operation(summary = "选择模拟设备")
    public Map<String, Object> selectDevice(@RequestBody DeviceSelectRequest request) {
        return Map.of("message", "已选择设备 " + request.deviceId(), "state", fleet.selectInteractive(request.deviceId()));
    }

    @GetMapping("/fleet")
    @Operation(summary = "查询并发模拟设备")
    public Object fleet() {
        return fleet.states();
    }

    @PostMapping("/fleet/{deviceId}/start")
    @Operation(summary = "启动指定设备的并发模拟")
    public Object startFleet(@Parameter(description = "设备 ID") @PathVariable Long deviceId) {
        return fleet.start(deviceId);
    }

    @PostMapping("/fleet/{deviceId}/stop")
    @Operation(summary = "停止指定设备的并发模拟")
    public Object stopFleet(@Parameter(description = "设备 ID") @PathVariable Long deviceId) {
        fleet.stop(deviceId);
        return Map.of("message", "已停止模拟");
    }

    @PostMapping("/fleet/{deviceId}/guns/{gunNo}/{action}")
    @Operation(summary = "控制并发模拟设备枪口")
    public Object fleetAction(
            @Parameter(description = "设备 ID") @PathVariable Long deviceId,
            @Parameter(description = "枪号") @PathVariable String gunNo,
            @Parameter(description = "枪口动作，例如 plug、start、stop") @PathVariable String action,
            @Parameter(description = "可选故障码") @RequestParam(required = false) String code) {
        return fleet.action(deviceId, gunNo, action, code);
    }

    public record ProtocolRequest(
            @Schema(description = "协议编码；实际协议由设备档案决定") String protocol) {}

    @PostMapping("/protocol")
    @Operation(summary = "设置模拟协议", description = "当前设备协议由设备档案决定，此接口保留用于兼容旧客户端")
    public Object protocol(@RequestBody ProtocolRequest request) {
        throw new IllegalArgumentException("协议由设备档案决定，请从设备列表重新选桩");
    }

    @PostMapping("/connect")
    @Operation(summary = "连接模拟设备")
    public Map<String, Object> connect() {
        return Map.of("connected", runtime.connect(), "state", runtime.state());
    }

    @PostMapping("/disconnect")
    @Operation(summary = "断开模拟设备")
    public Map<String, Object> disconnect() {
        runtime.disconnect();
        return Map.of("connected", false);
    }

    @PostMapping("/guns/{gunNo}/plug")
    @Operation(summary = "模拟插枪")
    public Map<String, Object> plug(@Parameter(description = "枪号") @PathVariable String gunNo) {
        return Map.of("message", runtime.plug(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/unplug")
    @Operation(summary = "模拟拔枪")
    public Map<String, Object> unplug(@Parameter(description = "枪号") @PathVariable String gunNo) {
        return Map.of("message", runtime.unplug(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/start")
    @Operation(summary = "模拟启动充电")
    public Map<String, Object> start(@Parameter(description = "枪号") @PathVariable String gunNo) {
        return Map.of("message", runtime.startByCard(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/stop")
    @Operation(summary = "模拟停止充电")
    public Map<String, Object> stop(@Parameter(description = "枪号") @PathVariable String gunNo) {
        return Map.of("message", runtime.stopLocal(gunNo), "state", runtime.state());
    }

    @PostMapping("/guns/{gunNo}/fault")
    @Operation(summary = "设置或清除模拟故障")
    public Map<String, Object> fault(
            @PathVariable String gunNo,
            @RequestParam(defaultValue = "true") boolean enable,
            @RequestParam(required = false) String code) {
        return Map.of(
                "message",
                code == null ? runtime.fault(gunNo, enable) : runtime.fault(gunNo, enable, code),
                "state",
                runtime.state());
    }

    @PostMapping("/guns/{gunNo}/report")
    @Operation(summary = "立即上报实时数据")
    public Map<String, Object> report(@Parameter(description = "枪号") @PathVariable String gunNo) {
        return Map.of("message", runtime.reportRealtime(gunNo), "state", runtime.state());
    }

    @GetMapping("/stop-reasons")
    @Operation(summary = "查询充电停止原因编码")
    public Object stopReasons() {
        return top.yealike.pep.codec.StopReasons.ALL;
    }

    @PostMapping("/guns/{gunNo}/terminate")
    @Operation(summary = "模拟异常终止充电")
    public Object terminate(@PathVariable String gunNo, @RequestParam int reason) {
        return Map.of("message", runtime.terminate(gunNo, reason));
    }

    public record ChargeLimits(
            @Schema(description = "充电电量上限，kWh") double energy,
            @Schema(description = "充电金额上限") double amount,
            @Schema(description = "充电时长上限，分钟") int minutes) {}

    @PostMapping("/guns/{gunNo}/limits")
    @Operation(summary = "设置充电限制")
    public Object limits(
            @PathVariable String gunNo, @org.springframework.web.bind.annotation.RequestBody ChargeLimits r) {
        runtime.chargeLimits(gunNo, r.energy(), r.amount(), r.minutes());
        return runtime.state();
    }

    public record UpgradeScenario(
            @Schema(description = "模拟升级结果编码") int result) {}

    @PostMapping("/upgrade-scenario")
    @Operation(summary = "设置固件升级模拟结果")
    public Object upgradeScenario(@org.springframework.web.bind.annotation.RequestBody UpgradeScenario r) {
        runtime.upgradeScenario(r.result());
        return Map.of("result", r.result());
    }

    public record ClockRequest(
            @Schema(description = "模拟时钟时间") LocalDateTime time,
            @Schema(description = "模拟时间流速倍数") Double speed,
            @Schema(description = "向前推进秒数") Integer advanceSeconds) {}

    @PostMapping("/clock")
    @Operation(summary = "设置模拟时钟")
    public Object clock(@org.springframework.web.bind.annotation.RequestBody ClockRequest request) {
        return runtime.configureClock(request.time(), request.speed(), request.advanceSeconds());
    }

    public record VehicleRequest(
            @Schema(description = "车辆电池容量，kWh") double capacityKwh,
            @Schema(description = "初始电量百分比") int soc,
            @Schema(description = "车辆 VIN") String vin,
            @Schema(description = "物理充电卡号") String physicalCard) {}

    @PostMapping("/guns/{gunNo}/vehicle")
    @Operation(summary = "配置模拟车辆")
    public Object vehicle(
            @PathVariable String gunNo, @org.springframework.web.bind.annotation.RequestBody VehicleRequest r) {
        runtime.configureVehicle(gunNo, r.capacityKwh(), r.soc(), r.vin(), r.physicalCard());
        return runtime.state();
    }

    @PostMapping("/guns/{gunNo}/offline-start")
    @Operation(summary = "模拟离线启动")
    public Object offline(@PathVariable String gunNo) {
        return Map.of("message", runtime.startOffline(gunNo));
    }

    @PostMapping("/guns/{gunNo}/vin-start")
    @Operation(summary = "使用 VIN 模拟启动")
    public Object vin(@PathVariable String gunNo) {
        return Map.of("message", runtime.startVin(gunNo));
    }

    @PostMapping("/parallel-start")
    @Operation(summary = "启动并行模拟")
    public Object parallel() {
        return Map.of("message", runtime.startParallel());
    }

    @GetMapping("/logs")
    @Operation(summary = "查询协议交互日志")
    public List<SimulatorRuntime.FrameLog> logs(
            @Parameter(description = "最多返回条数", example = "100") @RequestParam(defaultValue = "100") int limit) {
        return runtime.logs(limit);
    }

    @GetMapping("/frames")
    @Operation(summary = "查询协议帧日志")
    public List<SimulatorRuntime.FrameLog> frames(
            @Parameter(description = "最多返回条数", example = "100") @RequestParam(defaultValue = "100") int limit) {
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
