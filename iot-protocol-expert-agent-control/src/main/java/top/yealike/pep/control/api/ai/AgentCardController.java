package top.yealike.pep.control.api.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.AgentService;
import top.yealike.pep.control.agents.domain.AgentCapabilityCard;

/** 通过现有会话身份读取内部能力卡片。 */
@RestController
@RequestMapping("/api/ai/v1/agents")
@Tag(name = "Agent 能力卡片")
public class AgentCardController {

    private final AgentService agents;

    public AgentCardController(AgentService agents) {
        this.agents = agents;
    }

    /** 校验用户权限并返回版本化能力卡片。 */
    @GetMapping("/{id}/card")
    @Operation(summary = "查询已发布的 Agent 能力卡片")
    public ResponseEntity<AgentCapabilityCard> get(@Parameter(description = "Agent 组件 ID") @PathVariable UUID id) {
        var snapshot = agents.card(CurrentUser.require(), id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag("\"" + snapshot.manifestDigest() + "\"")
                .body(snapshot.card());
    }
}
