package top.yealike.pep.simulator.domain;

/** 模拟器当前使用的充电协议。 */
public enum Protocol {
    YKC16("ykc16", "云快充 1.6"),
    OCPP16J("ocpp16j", "OCPP 1.6J");

    private final String code;
    private final String label;

    Protocol(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Protocol of(String value) {
        if (value == null || value.isBlank()) {
            return YKC16;
        }
        for (Protocol protocol : values()) {
            if (protocol.code.equalsIgnoreCase(value.trim()) || protocol.name().equalsIgnoreCase(value.trim())) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("未知协议: " + value);
    }
}
