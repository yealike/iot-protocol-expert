package top.yealike.ipe.ops.api.admin;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.domain.CommandRecord;
import top.yealike.ipe.ops.service.CommandService;

@RestController
@RequestMapping("/api/ops/commands")
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public CommandRecord submit(@RequestBody Submit request, Principal principal) {
        return commands.submit(request.deviceId(), request.type(), request.params(), principal.getName());
    }

    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
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
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public Map<String, Object> stop(@RequestBody Stop request, Principal principal) {
        return commands.remoteStop(request.deviceId(), request.gunNo(), principal.getName());
    }

    @GetMapping
    public List<CommandRecord> list(@RequestParam(required = false) Long deviceId) {
        return commands.list(deviceId);
    }

    public record Submit(Long deviceId, String type, Map<String, Object> params) {}

    public record Start(Long deviceId, String gunNo, String idTag, String physicalCard, String balance) {}

    public record Stop(Long deviceId, String gunNo) {}
}
