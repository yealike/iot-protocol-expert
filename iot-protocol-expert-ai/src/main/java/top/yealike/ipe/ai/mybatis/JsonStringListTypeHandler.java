package top.yealike.ipe.ai.mybatis;

import java.util.List;
import top.yealike.ipe.ai.support.JsonSupport;

public class JsonStringListTypeHandler extends JsonTypeHandler<List<String>> {
    @Override
    protected List<String> parse(String value) {
        return JsonSupport.readStringList(value);
    }
}
