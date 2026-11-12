package top.yealike.ipe.ops.api.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.domain.CommandRecord;
import top.yealike.ipe.ops.service.CommandService;

/** 模拟器专用命令入口，使用模拟器只读令牌之外的限定写能力。 */
@RestController
@RequestMapping("/api/ops/simulator/commands")
public class SimulatorCommandController {

    private final CommandService commands;

    public SimulatorCommandController(CommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    public Map<String, Object> submit(@RequestBody Submit request) {
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
    public Map<String, Object> start(@RequestBody Start request) {
        return commands.remoteStart(
                request.deviceId(),
                request.gunNo(),
                request.idTag(),
                request.physicalCard(),
                request.balance(),
                "simulator");
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestBody Stop request) {
        return commands.remoteStop(request.deviceId(), request.gunNo(), "simulator");
    }

    public record Submit(Long deviceId, String type, Map<String, Object> params) {}

    public record Start(Long deviceId, String gunNo, String idTag, String physicalCard, String balance) {}

    public record Stop(Long deviceId, String gunNo) {}
}
