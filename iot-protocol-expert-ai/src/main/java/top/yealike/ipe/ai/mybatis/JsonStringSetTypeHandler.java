package top.yealike.ipe.ai.mybatis;

import java.util.Set;
import top.yealike.ipe.ai.support.JsonSupport;

public class JsonStringSetTypeHandler extends JsonTypeHandler<Set<String>> {
    @Override
    protected Set<String> parse(String value) {
        return Set.copyOf(JsonSupport.readStringList(value));
    }
}
