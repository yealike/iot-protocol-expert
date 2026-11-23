package top.yealike.pep.control.mybatis;

import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.RunUsage;

public class RunUsageTypeHandler extends JsonTypeHandler<RunUsage> {

    @Override
    protected RunUsage parse(String value) {
        return value == null || value.isBlank() ? null : JsonSupport.read(value, RunUsage.class);
    }
}
