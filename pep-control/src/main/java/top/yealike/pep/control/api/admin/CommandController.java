package top.yealike.pep.control.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.control.charging.domain.CommandRecord;
import top.yealike.pep.control.charging.service.CommandService;

@RestController
@RequestMapping("/api/ops/commands")
@Tag(name = "设备命令", description = "向充电设备下发通用、远程启动和远程停止命令")
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    @Operation(summary = "下发通用设备命令")
    @PreAuthorize("hasAuthority('ops:operate')")
    public CommandRecord submit(@RequestBody Submit request, Principal principal) {
        return commands.submit(request.deviceId(), request.type(), request.params(), principal.getName());
    }

    @PostMapping("/start")
    @Operation(summary = "远程启动充电")
    @PreAuthorize("hasAuthority('ops:operate')")
    public Map<String, Object> start(@RequestBody Start request, Principal principal) {
        return commands.remoteStart(
                request.deviceId(),
                request.gunNo(),
                request.idTag(),
                request.physicalCard(),
                request.balance(),
                principal.getName());
    }

    @PostMapping("/stop")
    @Operation(summary = "远程停止充电")
    @PreAuthorize("hasAuthority('ops:operate')")
    public Map<String, Object> stop(@RequestBody Stop request, Principal principal) {
        return commands.remoteStop(request.deviceId(), request.gunNo(), principal.getName());
    }

    @GetMapping
    @Operation(summary = "查询命令记录")
    public List<CommandRecord> list(
            @Parameter(description = "按设备 ID 过滤") @RequestParam(required = false) Long deviceId) {
        return commands.list(deviceId);
    }

    public record Submit(
            @Schema(description = "设备 ID") Long deviceId,
            @Schema(description = "命令类型") String type,
            @Schema(description = "命令参数") Map<String, Object> params) {}

    public record Start(
            @Schema(description = "设备 ID") Long deviceId,
            @Schema(description = "枪号") String gunNo,
            @Schema(description = "鉴权标签") String idTag,
            @Schema(description = "实体卡号") String physicalCard,
            @Schema(description = "预授权余额") String balance) {}

    public record Stop(
            @Schema(description = "设备 ID") Long deviceId,
            @Schema(description = "枪号") String gunNo) {}
}
