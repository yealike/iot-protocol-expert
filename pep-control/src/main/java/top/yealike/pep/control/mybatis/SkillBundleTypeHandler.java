package top.yealike.pep.control.mybatis;

import top.yealike.pep.runtime.contract.SkillBundle;
import top.yealike.pep.control.support.JsonSupport;

public class SkillBundleTypeHandler extends JsonTypeHandler<SkillBundle> {
    @Override
    protected SkillBundle parse(String value) {
        return JsonSupport.read(value, SkillBundle.class);
    }
}
