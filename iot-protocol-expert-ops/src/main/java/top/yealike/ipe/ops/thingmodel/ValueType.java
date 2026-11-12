package top.yealike.ipe.ops.thingmodel;

import java.util.List;

/**
 * 物模型值类型。
 *
 * <p>设计取舍：值类型是「契约」而不是「实现」。属性、功能入参、功能出参、事件载荷、标签 共用同一套类型，前端据此渲染表单与格式化展示，服务端据此选择存储列与做范围校验。 这是 jetlinks
 * {@code org.jetlinks.core.metadata.types} 的最小可用子集。
 *
 * <p>数值类型统一落 {@code value_num}，文本落 {@code value_text}，复合结构落 {@code value_json}， 时间落 {@code
 * value_time}——见 {@link ThingValues}。
 */
public sealed interface ValueType {

    /** 类型标识，与物模型 JSON 的 {@code type} 字段一致。 */
    String typeId();

    /** 单位，仅数值类型有。 */
    default String unit() {
        return null;
    }

    /** 是否可用于数值比较（告警阈值判断）。 */
    default boolean numeric() {
        return this instanceof IntType
                || this instanceof LongType
                || this instanceof FloatType
                || this instanceof DoubleType
                || this instanceof DecimalType;
    }

    record IntType(Long min, Long max, String unit) implements ValueType {
        @Override
        public String typeId() {
            return "int";
        }
    }

    record LongType(Long min, Long max, String unit) implements ValueType {
        @Override
        public String typeId() {
            return "long";
        }
    }

    record FloatType(Double min, Double max, String unit, Integer scale) implements ValueType {
        @Override
        public String typeId() {
            return "float";
        }
    }

    record DoubleType(Double min, Double max, String unit, Integer scale) implements ValueType {
        @Override
        public String typeId() {
            return "double";
        }
    }

    record DecimalType(Double min, Double max, String unit, Integer scale) implements ValueType {
        @Override
        public String typeId() {
            return "decimal";
        }
    }

    record StringType(Integer maxLength) implements ValueType {
        @Override
        public String typeId() {
            return "string";
        }
    }

    record BoolType() implements ValueType {
        @Override
        public String typeId() {
            return "bool";
        }
    }

    record DateType(String format) implements ValueType {
        @Override
        public String typeId() {
            return "date";
        }
    }

    record DateTimeType(String format) implements ValueType {
        @Override
        public String typeId() {
            return "dateTime";
        }
    }

    record EnumElement(String value, String text) {}

    record EnumType(List<EnumElement> elements) implements ValueType {
        @Override
        public String typeId() {
            return "enum";
        }

        public boolean accepts(String value) {
            return elements.stream().anyMatch(e -> e.value().equals(value));
        }

        public String textOf(String value) {
            return elements.stream()
                    .filter(e -> e.value().equals(value))
                    .map(EnumElement::text)
                    .findFirst()
                    .orElse(value);
        }
    }

    record ObjectType(List<ThingModel.PropertyMetadata> properties) implements ValueType {
        @Override
        public String typeId() {
            return "object";
        }
    }

    record ArrayType(ValueType elementType) implements ValueType {
        @Override
        public String typeId() {
            return "array";
        }
    }

    record GeoType() implements ValueType {
        @Override
        public String typeId() {
            return "geo";
        }
    }

    /** 未知类型兜底：仍可存取，但不做范围校验。 */
    record UnknownType(String raw) implements ValueType {
        @Override
        public String typeId() {
            return raw == null || raw.isBlank() ? "unknown" : raw;
        }
    }

    /** 常用类型常量，供 Java 侧定义物模型时复用。 */
    static ValueType string() {
        return new StringType(255);
    }
}
