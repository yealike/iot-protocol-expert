package top.yealike.ipe.ai.mybatis;

import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.support.JsonSupport;

public class AgentManifestTypeHandler extends JsonTypeHandler<AgentManifest> {
    @Override
    protected AgentManifest parse(String value) {
        return JsonSupport.read(value, AgentManifest.class);
    }
}
