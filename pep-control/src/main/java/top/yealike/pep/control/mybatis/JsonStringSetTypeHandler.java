package top.yealike.pep.control.mybatis;

import java.util.Set;
import top.yealike.pep.control.support.JsonSupport;

public class JsonStringSetTypeHandler extends JsonTypeHandler<Set<String>> {
    @Override
    protected Set<String> parse(String value) {
        return Set.copyOf(JsonSupport.readStringList(value));
    }
}
