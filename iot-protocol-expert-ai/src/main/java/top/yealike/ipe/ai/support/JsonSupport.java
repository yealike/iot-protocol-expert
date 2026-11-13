package top.yealike.ipe.ai.support;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 统一 JSON 读写。使用 Spring Boot 4 自带的 Jackson 3，避免与 AgentScope 的 Jackson 2 混用。 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonSupport() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    public static <T> List<T> readList(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(value, MAPPER.getTypeFactory().constructCollectionType(List.class, type));
    }

    public static List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(value, new TypeReference<List<String>>() {});
    }
}
