package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.AgentTeamService;
import top.yealike.pep.control.agents.domain.AgentTeam;
import top.yealike.pep.control.api.ai.admin.dto.AgentTeamRequest;

/** Agent 草稿的委派配置接口。 */
@RestController
@RequestMapping("/api/ai/v1/agents")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "Agent 委派管理")
public class AgentTeamController {

    private final AgentTeamService teams;

    public AgentTeamController(AgentTeamService teams) {
        this.teams = teams;
    }

    /** 读取 Agent 当前草稿的委派配置。 */
    @GetMapping("/{id}/team")
    @Operation(summary = "查询委派配置")
    public AgentTeam get(@Parameter(description = "Agent 组件 ID") @PathVariable UUID id) {
        return teams.get(id);
    }

    /** 使用修订号保存新的委派配置。 */
    @PutMapping("/{id}/delegates")
    @Operation(summary = "保存委派配置")
    public AgentTeam save(
            @Parameter(description = "Agent 组件 ID") @PathVariable UUID id,
            @Valid @RequestBody AgentTeamRequest request) {
        return teams.save(id, CurrentUser.require(), request);
    }
}
