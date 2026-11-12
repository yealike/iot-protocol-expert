package top.yealike.ipe.protocol.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.protocol.ops.OpsDtos;
import top.yealike.ipe.protocol.service.ChannelCommandService;

/** 运维平台调用的通道内部命令接口。 */
@RestController
@RequestMapping("/internal")
public class InternalCommandController {

    private final ChannelCommandService commands;

    public InternalCommandController(ChannelCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/commands")
    public OpsDtos.CommandResult execute(@RequestBody OpsDtos.CommandRequest request) {
        return commands.execute(request);
    }
}
