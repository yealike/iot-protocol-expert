package top.yealike.ipe.ops.api.admin;

import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.ops.domain.CommandRecord;
import top.yealike.ipe.ops.service.SimulationCommandService;

@RestController
@RequestMapping("/api/ops/simulation/commands")
public class SimulationCommandController {
    private final SimulationCommandService commands;

    public SimulationCommandController(SimulationCommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public CommandRecord submit(@RequestBody Request request, Principal principal) {
        return commands.submit(request.deviceId(), request.action(), principal.getName());
    }

    public record Request(Long deviceId, String action) {}
}
