package top.yealike.ipe.ai.mybatis;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import top.yealike.ipe.ai.support.JsonSupport;

public class JsonMapListTypeHandler extends JsonTypeHandler<List<Map<String, Object>>> {
    @Override
    protected List<Map<String, Object>> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return JsonSupport.mapper().readValue(value, new TypeReference<List<Map<String, Object>>>() {});
    }
}
