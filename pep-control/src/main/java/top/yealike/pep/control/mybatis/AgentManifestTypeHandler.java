package top.yealike.pep.control.mybatis;

import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.control.support.JsonSupport;

public class AgentManifestTypeHandler extends JsonTypeHandler<AgentManifest> {
    @Override
    protected AgentManifest parse(String value) {
        return JsonSupport.read(value, AgentManifest.class);
    }
}
