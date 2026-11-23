package top.yealike.pep.ops.devices.thingmodel.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
