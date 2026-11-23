package top.yealike.pep.gateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.gateway.command.ChannelCommandService;
import top.yealike.pep.gateway.command.dto.CommandRequest;
import top.yealike.pep.gateway.command.dto.CommandResult;

/** 运维平台调用的通道内部命令接口。 */
@RestController
@RequestMapping(path = "/internal", produces = "application/json")
@Tag(name = "协议通道-内部命令", description = "运维平台下发的远程启停、卡片同步等通道命令")
@SecurityRequirement(name = "opsServiceToken")
public class InternalCommandController {

    private final ChannelCommandService commands;

    public InternalCommandController(ChannelCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/commands")
    @Operation(operationId = "executeChannelCommand", summary = "执行通道命令")
    public CommandResult execute(@RequestBody CommandRequest request) {
        return commands.execute(request);
    }
}
