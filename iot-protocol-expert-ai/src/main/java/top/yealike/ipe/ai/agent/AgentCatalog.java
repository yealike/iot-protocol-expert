package top.yealike.ipe.ai.agent;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.support.JsonSupport;

/** Agent 定义目录：只暴露已发布版本，Run 开始时固定版本。 */
@Service
public class AgentCatalog {

    public static final String TEAM_LEADER = "team-leader";
    public static final String PROTOCOL_EXPERT = "protocol-expert";
    public static final String ORDER_SERVICE = "order-service";

    private final JdbcTemplate jdbc;

    public AgentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return jdbc.query("select * from ai_agent_definition where status = 'PUBLISHED' order by agent_key", this::map);
    }

    public Definition find(String agentKey) {
        return jdbc
                .query(
                        "select * from ai_agent_definition where agent_key = ? and status = 'PUBLISHED' "
                                + "order by published_at desc limit 1",
                        this::map,
                        agentKey)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Definition map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Definition(
                rs.getString("agent_key"),
                rs.getString("version"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("description"),
                rs.getString("sys_prompt"),
                rs.getString("model_key"),
                Set.copyOf(JsonSupport.readStringList(rs.getString("allowed_tools"))),
                rs.getString("status"));
    }
}
