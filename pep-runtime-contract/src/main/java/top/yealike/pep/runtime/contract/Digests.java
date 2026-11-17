package top.yealike.pep.runtime.contract;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统一规范序列化：JSON 键排序、数组保持语义顺序、UTF-8、SHA-256 小写 hex。
 *
 * <p>不依赖具体 Jackson 版本，避免 Control 与 Runtime 对数字格式处理不同。
 */
public final class Digests {

    private Digests() {}

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(Object value) {
        return sha256Hex(canonicalJson(value));
    }

    /** 规范 JSON：对象键按字典序，数字使用去尾零的十进制表示。 */
    public static String canonicalJson(Object value) {
        StringBuilder builder = new StringBuilder(256);
        append(builder, value);
        return builder.toString();
    }

    private static void append(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String text) {
            appendString(builder, text);
            return;
        }
        if (value instanceof Boolean || value instanceof Character) {
            builder.append(value);
            return;
        }
        if (value instanceof Number number) {
            builder.append(number(number));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendMap(builder, map);
            return;
        }
        if (value instanceof Collection<?> collection) {
            appendList(builder, collection);
            return;
        }
        if (value.getClass().isArray()) {
            appendList(builder, arrayToList(value));
            return;
        }
        appendString(builder, String.valueOf(value));
    }

    private static void appendMap(StringBuilder builder, Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((key, value) -> sorted.put(String.valueOf(key), value));
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            appendString(builder, entry.getKey());
            builder.append(':');
            append(builder, entry.getValue());
        }
        builder.append('}');
    }

    private static void appendList(StringBuilder builder, Collection<?> values) {
        builder.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            append(builder, value);
        }
        builder.append(']');
    }

    private static List<Object> arrayToList(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(java.lang.reflect.Array.get(array, index));
        }
        return values;
    }

    private static String number(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("摘要不支持非有限数值");
            }
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        return number.toString();
    }

    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}
