package top.yealike.ipe.ai.agent;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.mapper.AgentDefinitionMapper;

/** Agent 定义目录：只暴露已发布版本，Run 开始时固定版本。 */
@Service
public class AgentCatalog {

    public static final String TEAM_LEADER = "team-leader";
    public static final String PROTOCOL_EXPERT = "protocol-expert";
    public static final String ORDER_SERVICE = "order-service";

    private final AgentDefinitionMapper mapper;

    public AgentCatalog(AgentDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    public record Definition(
            String agentKey,
            String version,
            String name,
            String kind,
            String description,
            String sysPrompt,
            String modelKey,
            Set<String> allowedTools,
            String status) {}

    public List<Definition> listPublished() {
        return mapper.listPublished();
    }

    public Definition find(String agentKey) {
        return mapper.find(agentKey).stream().findFirst().orElse(null);
    }
}
