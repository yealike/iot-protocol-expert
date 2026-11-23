package top.yealike.pep.ops.api.simulator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.simulator.dto.SimulatorCommandSubmit;
import top.yealike.pep.ops.api.simulator.dto.SimulatorStartCommand;
import top.yealike.pep.ops.api.simulator.dto.SimulatorStopCommand;
import top.yealike.pep.ops.charging.domain.CommandRecord;
import top.yealike.pep.ops.charging.service.CommandService;

/** 模拟器专用命令入口，使用模拟器只读令牌之外的限定写能力。 */
@RestController
@RequestMapping(path = "/api/ops/simulator/commands", produces = "application/json")
@Tag(name = "模拟器命令", description = "模拟器以受限服务令牌触发设备启停与自定义指令")
@SecurityRequirement(name = "simulatorToken")
public class SimulatorCommandController {

    private final CommandService commands;

    public SimulatorCommandController(CommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    @Operation(operationId = "submitSimulatorCommand", summary = "提交自定义设备命令")
    public Map<String, Object> submit(@RequestBody SimulatorCommandSubmit request) {
        CommandRecord record = commands.submit(
                request.deviceId(),
                request.type(),
                request.params() == null ? Map.of() : request.params(),
                "simulator");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "success",
                record.status == CommandRecord.Status.SUCCESS || record.status == CommandRecord.Status.WAITING_STATE);
        result.put("status", record.status.name());
        result.put("commandId", record.id);
        result.put("message", record.responsePayload);
        return result;
    }

    @PostMapping("/start")
    @Operation(operationId = "startSimulatorCharging", summary = "模拟器启动充电")
    public Map<String, Object> start(@RequestBody SimulatorStartCommand request) {
        return commands.remoteStart(
                request.deviceId(),
                request.gunNo(),
                request.idTag(),
                request.physicalCard(),
                request.balance(),
                "simulator");
    }

    @PostMapping("/stop")
    @Operation(operationId = "stopSimulatorCharging", summary = "模拟器停止充电")
    public Map<String, Object> stop(@RequestBody SimulatorStopCommand request) {
        return commands.remoteStop(request.deviceId(), request.gunNo(), "simulator");
    }
}
