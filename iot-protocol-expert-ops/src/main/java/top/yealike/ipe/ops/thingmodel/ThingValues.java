package top.yealike.ipe.ops.thingmodel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 属性值的强制转换、范围校验与比较。
 *
 * <p>存储模型是「一个属性一行、按类型选列」：数值进 {@code value_num}，文本进 {@code value_text}，复合结构进 {@code value_json}，时间进
 * {@code value_time}。 这里负责把协议通道送来的原始值翻译成这套列，并在翻译时完成物模型声明的范围校验—— 校验发生在**入库前**，不是查询时。
 */
public final class ThingValues {

    private ThingValues() {}

    /** 归一化后的属性值。四个列互斥，只有与类型对应的一列有值。 */
    public record StoredValue(String valueType, BigDecimal num, String text, String json, LocalDateTime time) {

        public boolean isNull() {
            return num == null && text == null && json == null && time == null;
        }

        public static StoredValue ofText(String type, String text) {
            return new StoredValue(type, null, text, null, null);
        }

        public static StoredValue ofNum(String type, BigDecimal value) {
            return new StoredValue(type, value, null, null, null);
        }
    }

    /**
     * 把原始值转成可入库的列值。
     *
     * @throws IllegalArgumentException 值不符合物模型声明（越界、枚举不匹配、类型不兼容）
     */
    public static StoredValue coerce(ValueType type, Object raw) {
        if (raw == null) {
            return new StoredValue(type == null ? "unknown" : type.typeId(), null, null, null, null);
        }
        if (type == null) {
            type = new ValueType.UnknownType("unknown");
        }
        String typeId = type.typeId();

        return switch (type) {
            case ValueType.IntType t -> StoredValue.ofNum(typeId, checkRange(t.min(), t.max(), toDecimal(raw, typeId)));
            case ValueType.LongType t ->
                StoredValue.ofNum(typeId, checkRange(t.min(), t.max(), toDecimal(raw, typeId)));
            case ValueType.FloatType t ->
                StoredValue.ofNum(typeId, checkRange(t.min(), t.max(), toDecimal(raw, typeId)));
            case ValueType.DoubleType t ->
                StoredValue.ofNum(typeId, checkRange(t.min(), t.max(), toDecimal(raw, typeId)));
            case ValueType.DecimalType t ->
                StoredValue.ofNum(typeId, checkRange(t.min(), t.max(), toDecimal(raw, typeId)));
            case ValueType.BoolType ignored ->
                StoredValue.ofNum(typeId, toBool(raw) ? BigDecimal.ONE : BigDecimal.ZERO);
            case ValueType.StringType t -> {
                String text = String.valueOf(raw);
                if (t.maxLength() != null && text.length() > t.maxLength()) {
                    throw new IllegalArgumentException("字符串超出最大长度 " + t.maxLength() + ": " + text);
                }
                yield StoredValue.ofText(typeId, text);
            }
            case ValueType.EnumType t -> {
                String text = String.valueOf(raw);
                if (!t.elements().isEmpty() && !t.accepts(text)) {
                    throw new IllegalArgumentException("枚举值不在定义范围: " + text);
                }
                yield StoredValue.ofText(typeId, text);
            }
            case ValueType.DateType t ->
                new StoredValue(
                        typeId,
                        null,
                        null,
                        null,
                        toDateTime(raw, t.format()).toLocalDate().atStartOfDay());
            case ValueType.DateTimeType t -> new StoredValue(typeId, null, null, null, toDateTime(raw, t.format()));
            case ValueType.ObjectType ignored -> new StoredValue(typeId, null, null, toJson(raw), null);
            case ValueType.ArrayType ignored -> new StoredValue(typeId, null, null, toJson(raw), null);
            case ValueType.GeoType ignored -> new StoredValue(typeId, null, null, toJson(raw), null);
            case ValueType.UnknownType ignored -> StoredValue.ofText(typeId, String.valueOf(raw));
        };
    }

    private static BigDecimal checkRange(Double min, Double max, BigDecimal value) {
        return checkRange(
                min == null ? null : BigDecimal.valueOf(min), max == null ? null : BigDecimal.valueOf(max), value);
    }

    private static BigDecimal checkRange(Long min, Long max, BigDecimal value) {
        return checkRange(
                min == null ? null : BigDecimal.valueOf(min), max == null ? null : BigDecimal.valueOf(max), value);
    }

    private static BigDecimal checkRange(BigDecimal min, BigDecimal max, BigDecimal value) {
        if (min != null && value.compareTo(min) < 0) {
            throw new IllegalArgumentException("低于下限 " + min.toPlainString() + ": " + value.toPlainString());
        }
        if (max != null && value.compareTo(max) > 0) {
            throw new IllegalArgumentException("超过上限 " + max.toPlainString() + ": " + value.toPlainString());
        }
        return value;
    }

    public static BigDecimal toDecimal(Object raw, String typeId) {
        if (raw instanceof BigDecimal d) {
            return d;
        }
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (raw instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("数值属性收到空字符串");
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无法把 '" + trimmed + "' 解析为 " + typeId, e);
            }
        }
        throw new IllegalArgumentException("无法把 " + raw.getClass().getSimpleName() + " 解析为 " + typeId);
    }

    public static boolean toBool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            return "1".equals(trimmed) || "true".equalsIgnoreCase(trimmed) || "on".equalsIgnoreCase(trimmed);
        }
        return false;
    }

    private static LocalDateTime toDateTime(Object raw, String format) {
        if (raw instanceof LocalDateTime time) {
            return time;
        }
        if (raw instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (raw instanceof Number n) {
            long epochMillis = n.longValue();
            // 秒级时间戳兜底：小于 1e11 视为秒
            if (epochMillis < 100_000_000_000L) {
                epochMillis *= 1000;
            }
            return LocalDateTime.ofEpochSecond(epochMillis / 1000, 0, java.time.ZoneOffset.ofHours(8));
        }
        String text = String.valueOf(raw).trim();
        if (format != null && !format.isBlank()) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(format));
            } catch (DateTimeParseException ignored) {
                // 落到下面的标准格式
            }
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("无法把 '" + text + "' 解析为时间", e);
        }
    }

    private static String toJson(Object raw) {
        try {
            return ThingModelCodec.mapper().writeValueAsString(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("复合属性无法序列化为 JSON", e);
        }
    }

    // ------------------------------------------------------------------ 比较与展示

    /** 告警阈值判断。{@code actual} 为空时一律不触发。 */
    public static boolean compare(String operator, BigDecimal actual, BigDecimal threshold) {
        if (actual == null || threshold == null || operator == null) {
            return false;
        }
        int cmp = actual.compareTo(threshold);
        return switch (operator) {
            case "gt" -> cmp > 0;
            case "ge" -> cmp >= 0;
            case "lt" -> cmp < 0;
            case "le" -> cmp <= 0;
            case "eq" -> cmp == 0;
            case "ne" -> cmp != 0;
            default -> false;
        };
    }

    /** 展示用文本：数值按 scale 定点，枚举取中文，时间格式化，布尔转中文。 */
    public static String format(ValueType type, StoredValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (type == null) {
            return value.num() != null
                    ? value.num().toPlainString()
                    : value.text() != null ? value.text() : value.json();
        }
        return switch (type) {
            case ValueType.IntType ignored ->
                value.num() == null ? null : value.num().stripTrailingZeros().toPlainString();
            case ValueType.LongType ignored ->
                value.num() == null ? null : value.num().stripTrailingZeros().toPlainString();
            case ValueType.FloatType t -> scaleOf(value.num(), t.scale());
            case ValueType.DoubleType t -> scaleOf(value.num(), t.scale());
            case ValueType.DecimalType t -> scaleOf(value.num(), t.scale());
            case ValueType.BoolType ignored -> Boolean.TRUE.equals(toBool(value.num())) ? "是" : "否";
            case ValueType.EnumType t -> value.text() == null ? null : t.textOf(value.text());
            case ValueType.DateType ignored ->
                value.time() == null ? null : value.time().toLocalDate().toString();
            case ValueType.DateTimeType ignored ->
                value.time() == null ? null : value.time().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            case ValueType.StringType ignored -> value.text();
            case ValueType.ObjectType ignored -> value.json();
            case ValueType.ArrayType ignored -> value.json();
            case ValueType.GeoType ignored -> value.json();
            case ValueType.UnknownType ignored -> value.text() != null ? value.text() : value.json();
        };
    }

    private static String scaleOf(BigDecimal value, Integer scale) {
        if (value == null) {
            return null;
        }
        return scale == null
                ? value.stripTrailingZeros().toPlainString()
                : value.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean toBool(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    /** 物模型里某个属性是否为数值型，供告警规则筛选。 */
    public static List<String> numericPropertyIds(ThingModel model) {
        return model.properties().stream()
                .filter(p -> p.valueType() != null && p.valueType().numeric())
                .map(ThingModel.PropertyMetadata::id)
                .toList();
    }
}
