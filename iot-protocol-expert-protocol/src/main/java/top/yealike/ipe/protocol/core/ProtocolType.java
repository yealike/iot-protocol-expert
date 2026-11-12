package top.yealike.ipe.protocol.core;

/** 平台支持的充电协议。 */
public enum ProtocolType {
    YKC16("YKC16", "云快充 1.6", "tcp"),
    OCPP16J("OCPP16J", "OCPP 1.6J", "websocket");

    private final String code;
    private final String label;
    private final String transport;

    ProtocolType(String code, String label, String transport) {
        this.code = code;
        this.label = label;
        this.transport = transport;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String transport() {
        return transport;
    }

    public static ProtocolType of(String code) {
        if (code == null || code.isBlank()) {
            return YKC16;
        }
        for (ProtocolType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim()) || type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知协议: " + code);
    }
}
