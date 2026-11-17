package top.yealike.pep.control.mybatis;

import java.util.List;
import top.yealike.pep.control.support.JsonSupport;

public class JsonStringListTypeHandler extends JsonTypeHandler<List<String>> {
    @Override
    protected List<String> parse(String value) {
        return JsonSupport.readStringList(value);
    }
}
