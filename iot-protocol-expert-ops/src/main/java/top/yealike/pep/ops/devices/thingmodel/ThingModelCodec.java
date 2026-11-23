package top.yealike.pep.ops.devices.thingmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.ops.devices.thingmodel.domain.ArrayType;
import top.yealike.pep.ops.devices.thingmodel.domain.BoolType;
import top.yealike.pep.ops.devices.thingmodel.domain.DateTimeType;
import top.yealike.pep.ops.devices.thingmodel.domain.DateType;
import top.yealike.pep.ops.devices.thingmodel.domain.DecimalType;
import top.yealike.pep.ops.devices.thingmodel.domain.DoubleType;
import top.yealike.pep.ops.devices.thingmodel.domain.EnumElement;
import top.yealike.pep.ops.devices.thingmodel.domain.EnumType;
import top.yealike.pep.ops.devices.thingmodel.domain.EventMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.FloatType;
import top.yealike.pep.ops.devices.thingmodel.domain.FunctionMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.GeoType;
import top.yealike.pep.ops.devices.thingmodel.domain.IntType;
import top.yealike.pep.ops.devices.thingmodel.domain.LongType;
import top.yealike.pep.ops.devices.thingmodel.domain.ObjectType;
import top.yealike.pep.ops.devices.thingmodel.domain.PropertyMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.StringType;
import top.yealike.pep.ops.devices.thingmodel.domain.TagMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.ThingModel;
import top.yealike.pep.ops.devices.thingmodel.domain.UnknownType;
import top.yealike.pep.ops.devices.thingmodel.domain.ValueType;

/**
 * 物模型与 JSON 的显式互转。
 *
 * <p>不使用 Jackson 多态注解：值类型的 JSON 结构是产品对外的契约，宁可手写映射， 也不要让框架注解决定字段名。解析失败一律抛出 {@link
 * IllegalArgumentException}， 由 API 层转成 400。
 */
public final class ThingModelCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ThingModelCodec() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    // ------------------------------------------------------------------ 解析

    public static ThingModel parse(String json) {
        if (json == null || json.isBlank()) {
            return ThingModel.empty();
        }
        try {
            return fromMap(MAPPER.readValue(json, MAP_TYPE));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("物模型 JSON 无法解析: " + e.getMessage(), e);
        }
    }

    public static ThingModel fromMap(Map<String, Object> map) {
        if (map == null) {
            return ThingModel.empty();
        }
        return new ThingModel(
                parsePropertyList(map.get("properties")),
                parseFunctionList(map.get("functions")),
                parseEventList(map.get("events")),
                parseTagList(map.get("tags")));
    }

    /** 校验并归一化：解析后立刻跑一次 {@link ThingModel#validate()}。 */
    public static ThingModel parseAndValidate(String json) {
        ThingModel model = parse(json);
        List<String> problems = model.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("物模型不合法: " + String.join("; ", problems));
        }
        return model;
    }

    private static List<PropertyMetadata> parsePropertyList(Object raw) {
        List<PropertyMetadata> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            result.add(parseProperty(item));
        }
        return result;
    }

    private static PropertyMetadata parseProperty(Map<String, Object> item) {
        return new PropertyMetadata(
                str(item.get("id")),
                str(item.get("name")),
                parseValueType(item.get("valueType")),
                bool(item.get("channel"), false),
                bool(item.get("readOnly"), true),
                str(item.get("description")),
                map(item.get("expands")));
    }

    private static List<FunctionMetadata> parseFunctionList(Object raw) {
        List<FunctionMetadata> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            result.add(new FunctionMetadata(
                    str(item.get("id")),
                    str(item.get("name")),
                    bool(item.get("async"), true),
                    parsePropertyList(item.get("inputs")),
                    item.get("output") == null ? null : parseValueType(item.get("output")),
                    str(item.get("description"))));
        }
        return result;
    }

    private static List<EventMetadata> parseEventList(Object raw) {
        List<EventMetadata> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            result.add(new EventMetadata(
                    str(item.get("id")),
                    str(item.get("name")),
                    str(item.get("level")),
                    parseValueType(item.get("valueType")),
                    str(item.get("description"))));
        }
        return result;
    }

    private static List<TagMetadata> parseTagList(Object raw) {
        List<TagMetadata> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            result.add(new TagMetadata(
                    str(item.get("id")),
                    str(item.get("name")),
                    parseValueType(item.get("valueType")),
                    str(item.get("description"))));
        }
        return result;
    }

    public static ValueType parseValueType(Object raw) {
        Map<String, Object> map = map(raw);
        if (map.isEmpty()) {
            return new UnknownType("unknown");
        }
        String type = str(map.get("type"));
        if (type == null) {
            return new UnknownType("unknown");
        }
        return switch (type) {
            case "int" -> new IntType(longOrNull(map.get("min")), longOrNull(map.get("max")), str(map.get("unit")));
            case "long" -> new LongType(longOrNull(map.get("min")), longOrNull(map.get("max")), str(map.get("unit")));
            case "float" ->
                new FloatType(
                        doubleOrNull(map.get("min")),
                        doubleOrNull(map.get("max")),
                        str(map.get("unit")),
                        intOrNull(map.get("scale")));
            case "double" ->
                new DoubleType(
                        doubleOrNull(map.get("min")),
                        doubleOrNull(map.get("max")),
                        str(map.get("unit")),
                        intOrNull(map.get("scale")));
            case "decimal" ->
                new DecimalType(
                        doubleOrNull(map.get("min")),
                        doubleOrNull(map.get("max")),
                        str(map.get("unit")),
                        intOrNull(map.get("scale")));
            case "string" -> new StringType(intOrNull(map.get("maxLength")));
            case "bool", "boolean" -> new BoolType();
            case "date" -> new DateType(str(map.get("format")));
            case "dateTime", "datetime" -> new DateTimeType(str(map.get("format")));
            case "enum" -> new EnumType(parseEnumElements(map.get("elements")));
            case "object" -> new ObjectType(parsePropertyList(map.get("properties")));
            case "array" -> new ArrayType(parseValueType(map.get("elementType")));
            case "geo" -> new GeoType();
            default -> new UnknownType(type);
        };
    }

    private static List<EnumElement> parseEnumElements(Object raw) {
        List<EnumElement> elements = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            elements.add(new EnumElement(str(item.get("value")), str(item.get("text"))));
        }
        return elements;
    }

    // ------------------------------------------------------------------ 序列化

    public static Map<String, Object> toMap(ThingModel model) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (model == null || model.isEmpty()) {
            map.put("properties", List.of());
            map.put("functions", List.of());
            map.put("events", List.of());
            map.put("tags", List.of());
            return map;
        }
        map.put(
                "properties",
                model.properties().stream().map(ThingModelCodec::propertyToMap).toList());
        map.put(
                "functions",
                model.functions().stream().map(ThingModelCodec::functionToMap).toList());
        map.put(
                "events",
                model.events().stream().map(ThingModelCodec::eventToMap).toList());
        map.put("tags", model.tags().stream().map(ThingModelCodec::tagToMap).toList());
        return map;
    }

    public static String toJson(ThingModel model) {
        try {
            return MAPPER.writeValueAsString(toMap(model));
        } catch (RuntimeException e) {
            throw new IllegalStateException("物模型序列化失败", e);
        }
    }

    private static Map<String, Object> propertyToMap(PropertyMetadata property) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", property.id());
        map.put("name", property.name());
        map.put("valueType", valueTypeToMap(property.valueType()));
        if (property.channel()) {
            map.put("channel", true);
        }
        map.put("readOnly", property.readOnly());
        if (property.description() != null) {
            map.put("description", property.description());
        }
        if (property.expands() != null && !property.expands().isEmpty()) {
            map.put("expands", property.expands());
        }
        return map;
    }

    private static Map<String, Object> functionToMap(FunctionMetadata function) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", function.id());
        map.put("name", function.name());
        map.put("async", function.async());
        map.put(
                "inputs",
                function.inputs().stream().map(ThingModelCodec::propertyToMap).toList());
        if (function.output() != null) {
            map.put("output", valueTypeToMap(function.output()));
        }
        return map;
    }

    private static Map<String, Object> eventToMap(EventMetadata event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.id());
        map.put("name", event.name());
        map.put("level", event.levelOrInfo());
        map.put("valueType", valueTypeToMap(event.valueType()));
        return map;
    }

    private static Map<String, Object> tagToMap(TagMetadata tag) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tag.id());
        map.put("name", tag.name());
        map.put("valueType", valueTypeToMap(tag.valueType()));
        return map;
    }

    public static Map<String, Object> valueTypeToMap(ValueType type) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (type == null) {
            map.put("type", "unknown");
            return map;
        }
        map.put("type", type.typeId());
        switch (type) {
            case IntType t -> {
                putIfPresent(map, "min", t.min());
                putIfPresent(map, "max", t.max());
                putIfPresent(map, "unit", t.unit());
            }
            case LongType t -> {
                putIfPresent(map, "min", t.min());
                putIfPresent(map, "max", t.max());
                putIfPresent(map, "unit", t.unit());
            }
            case FloatType t -> {
                putIfPresent(map, "min", t.min());
                putIfPresent(map, "max", t.max());
                putIfPresent(map, "unit", t.unit());
                putIfPresent(map, "scale", t.scale());
            }
            case DoubleType t -> {
                putIfPresent(map, "min", t.min());
                putIfPresent(map, "max", t.max());
                putIfPresent(map, "unit", t.unit());
                putIfPresent(map, "scale", t.scale());
            }
            case DecimalType t -> {
                putIfPresent(map, "min", t.min());
                putIfPresent(map, "max", t.max());
                putIfPresent(map, "unit", t.unit());
                putIfPresent(map, "scale", t.scale());
            }
            case StringType t -> putIfPresent(map, "maxLength", t.maxLength());
            case DateType t -> putIfPresent(map, "format", t.format());
            case DateTimeType t -> putIfPresent(map, "format", t.format());
            case EnumType t -> map.put("elements", enumElementsToMap(t));
            case ObjectType t ->
                map.put(
                        "properties",
                        t.properties().stream()
                                .map(ThingModelCodec::propertyToMap)
                                .toList());
            case ArrayType t -> map.put("elementType", valueTypeToMap(t.elementType()));
            case BoolType ignored -> {}
            case GeoType ignored -> {}
            case UnknownType ignored -> {}
        }
        return map;
    }

    private static List<Map<String, Object>> enumElementsToMap(EnumType type) {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (EnumElement element : type.elements()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", element.value());
            item.put("text", element.text());
            elements.add(item);
        }
        return elements;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ------------------------------------------------------------------ 基础工具

    private static Map<String, Object> map(Object raw) {
        if (raw instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(map(item));
        }
        return result;
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static Long longOrNull(Object raw) {
        return raw instanceof Number n ? n.longValue() : null;
    }

    private static Double doubleOrNull(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer intOrNull(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }
}
