package top.yealike.pep.ops.devices.thingmodel.domain;

/**
 * 物模型值类型。
 *
 * <p>设计取舍：值类型是「契约」而不是「实现」。属性、功能入参、功能出参、事件载荷、标签 共用同一套类型，前端据此渲染表单与格式化展示，服务端据此选择存储列与做范围校验。 这是 jetlinks
 * {@code org.jetlinks.core.metadata.types} 的最小可用子集。
 *
 * <p>数值类型统一落 {@code value_num}，文本落 {@code value_text}，复合结构落 {@code value_json}， 时间落 {@code
 * value_time}——见 {@code ThingValues}。
 */
public sealed interface ValueType
        permits IntType,
                LongType,
                FloatType,
                DoubleType,
                DecimalType,
                StringType,
                BoolType,
                DateType,
                DateTimeType,
                EnumType,
                ObjectType,
                ArrayType,
                GeoType,
                UnknownType {

    /** 类型标识，与物模型 JSON 的 {@code type} 字段一致。 */
    String typeId();

    /** 单位，仅数值类型有。 */
    default String unit() {
        return null;
    }

    /** 常用类型常量，供 Java 侧定义物模型时复用。 */
    static ValueType string() {
        return new StringType(255);
    }
}
