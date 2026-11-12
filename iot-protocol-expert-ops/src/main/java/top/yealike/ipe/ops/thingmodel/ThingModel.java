package top.yealike.ipe.ops.thingmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 物模型：一个设备/产品能上报什么、能接受什么指令、会产生什么事件、有哪些静态标签。
 *
 * <p>四段结构（properties / functions / events / tags）与 jetlinks 一致，理由也一致：
 * 一份模型同时驱动表单渲染、值格式化、时序建表与告警字段选择。不要拆成多张元数据表， 否则改模型就要改 DDL。
 *
 * <p>产品层是模板，设备层是覆盖。{@link #merge(ThingModel)} 的语义是「覆盖层优先」： 同名 id 的条目整体替换，新增 id 追加。
 */
public record ThingModel(
        List<PropertyMetadata> properties,
        List<FunctionMetadata> functions,
        List<EventMetadata> events,
        List<TagMetadata> tags) {

    public ThingModel {
        properties = properties == null ? List.of() : List.copyOf(properties);
        functions = functions == null ? List.of() : List.copyOf(functions);
        events = events == null ? List.of() : List.copyOf(events);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static ThingModel empty() {
        return new ThingModel(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return properties.isEmpty() && functions.isEmpty() && events.isEmpty() && tags.isEmpty();
    }

    private static <T> Map<String, T> index(List<T> items, java.util.function.Function<T, String> id) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(id.apply(item), item);
        }
        return map;
    }

    public Map<String, PropertyMetadata> propertyIndex() {
        return index(properties, PropertyMetadata::id);
    }

    public Map<String, FunctionMetadata> functionIndex() {
        return index(functions, FunctionMetadata::id);
    }

    public Map<String, EventMetadata> eventIndex() {
        return index(events, EventMetadata::id);
    }

    public Map<String, TagMetadata> tagIndex() {
        return index(tags, TagMetadata::id);
    }

    public Optional<PropertyMetadata> property(String id) {
        return properties.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public Optional<EventMetadata> event(String id) {
        return events.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** 需要按通道分别上报的属性。 */
    public List<PropertyMetadata> channelProperties() {
        return properties.stream().filter(PropertyMetadata::channel).toList();
    }

    public List<PropertyMetadata> deviceProperties() {
        return properties.stream().filter(p -> !p.channel()).toList();
    }

    /** 覆盖层合并：同名 id 替换，新 id 追加。覆盖层为空时直接返回自身。 */
    public ThingModel merge(ThingModel overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return this;
        }
        return new ThingModel(
                mergeList(properties, overlay.properties, PropertyMetadata::id),
                mergeList(functions, overlay.functions, FunctionMetadata::id),
                mergeList(events, overlay.events, EventMetadata::id),
                mergeList(tags, overlay.tags, TagMetadata::id));
    }

    private static <T> List<T> mergeList(List<T> base, List<T> overlay, java.util.function.Function<T, String> id) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (T item : base) {
            merged.put(id.apply(item), item);
        }
        for (T item : overlay) {
            merged.put(id.apply(item), item);
        }
        return new ArrayList<>(merged.values());
    }

    /** 模型自查：id 不重复、功能入参不重复、枚举非空。返回问题清单，空表示通过。 */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        checkUnique(properties.stream().map(PropertyMetadata::id).toList(), "属性", problems);
        checkUnique(functions.stream().map(FunctionMetadata::id).toList(), "功能", problems);
        checkUnique(events.stream().map(EventMetadata::id).toList(), "事件", problems);
        checkUnique(tags.stream().map(TagMetadata::id).toList(), "标签", problems);

        for (PropertyMetadata property : properties) {
            if (property.id() == null || property.id().isBlank()) {
                problems.add("属性 id 不能为空");
            }
            if (property.valueType() == null) {
                problems.add("属性 " + property.id() + " 缺少 valueType");
            } else if (property.valueType() instanceof ValueType.EnumType e
                    && e.elements().isEmpty()) {
                problems.add("枚举属性 " + property.id() + " 未定义 elements");
            }
        }
        for (FunctionMetadata function : functions) {
            checkUnique(
                    function.inputs().stream().map(PropertyMetadata::id).toList(),
                    "功能 " + function.id() + " 的入参",
                    problems);
        }
        for (EventMetadata event : events) {
            if (event.valueType() == null) {
                problems.add("事件 " + event.id() + " 缺少 valueType");
            }
        }
        return problems;
    }

    private static void checkUnique(List<String> ids, String label, List<String> problems) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                problems.add(label + " id 重复: " + id);
            }
        }
    }

    public Map<String, Object> toMap() {
        return ThingModelCodec.toMap(this);
    }

    /**
     * 属性。{@code channel=true} 表示按通道上报（枪电压、组串电流、电池簇 SOC）， 存储键为 (deviceId, channelNo, propertyId)。
     */
    public record PropertyMetadata(
            String id,
            String name,
            ValueType valueType,
            boolean channel,
            boolean readOnly,
            String description,
            Map<String, Object> expands) {
        public PropertyMetadata {
            expands = expands == null ? Map.of() : Map.copyOf(expands);
        }

        public static PropertyMetadata of(String id, String name, ValueType valueType) {
            return new PropertyMetadata(id, name, valueType, false, true, null, Map.of());
        }

        public static PropertyMetadata writable(String id, String name, ValueType valueType) {
            return new PropertyMetadata(id, name, valueType, false, false, null, Map.of());
        }

        public static PropertyMetadata channel(String id, String name, ValueType valueType) {
            return new PropertyMetadata(id, name, valueType, true, true, null, Map.of());
        }

        public PropertyMetadata withChannel(boolean value) {
            return new PropertyMetadata(id, name, valueType, value, readOnly, description, expands);
        }
    }

    /** 功能（指令）。inputs/output 复用同一套值类型。 */
    public record FunctionMetadata(
            String id,
            String name,
            boolean async,
            List<PropertyMetadata> inputs,
            ValueType output,
            String description) {
        public FunctionMetadata {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    /** 事件。level 是默认告警级别，可被告警规则覆盖。 */
    public record EventMetadata(String id, String name, String level, ValueType valueType, String description) {
        public String levelOrInfo() {
            return level == null || level.isBlank() ? "INFO" : level;
        }
    }

    /** 静态标签：安装位置、额定功率、SIM 卡号这类不随时间变化的信息。 */
    public record TagMetadata(String id, String name, ValueType valueType, String description) {}

    @Override
    public boolean equals(Object o) {
        return o instanceof ThingModel other
                && properties.equals(other.properties)
                && functions.equals(other.functions)
                && events.equals(other.events)
                && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties, functions, events, tags);
    }
}
