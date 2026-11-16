package top.yealike.ipe.ai.mybatis;

import java.util.Map;
import top.yealike.ipe.ai.support.JsonSupport;

public class JsonMapTypeHandler extends JsonTypeHandler<Map<String, Object>> {
    @Override
    protected Map<String, Object> parse(String value) {
        return JsonSupport.readMap(value);
    }
}
