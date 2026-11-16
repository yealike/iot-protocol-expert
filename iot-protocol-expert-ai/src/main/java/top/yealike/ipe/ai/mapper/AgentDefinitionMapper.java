package top.yealike.ipe.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ai.agent.AgentCatalog;

public interface AgentDefinitionMapper {

    List<AgentCatalog.Definition> listPublished();

    List<AgentCatalog.Definition> find(@Param("agentKey") String agentKey);
}
