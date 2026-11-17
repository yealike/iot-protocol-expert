package top.yealike.ipe.ai.mybatis;

import top.yealike.ipe.ai.contract.SkillBundle;
import top.yealike.ipe.ai.support.JsonSupport;

public class SkillBundleTypeHandler extends JsonTypeHandler<SkillBundle> {
    @Override
    protected SkillBundle parse(String value) {
        return JsonSupport.read(value, SkillBundle.class);
    }
}
