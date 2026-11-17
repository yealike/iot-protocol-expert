package top.yealike.pep.control.mybatis;

import java.util.Map;
import top.yealike.pep.control.support.JsonSupport;

public class JsonMapTypeHandler extends JsonTypeHandler<Map<String, Object>> {
    @Override
    protected Map<String, Object> parse(String value) {
        return JsonSupport.readMap(value);
    }
}
