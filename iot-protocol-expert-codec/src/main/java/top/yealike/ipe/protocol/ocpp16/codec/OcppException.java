package top.yealike.ipe.protocol.ocpp16.codec;

/** OCPP 1.6J 消息编解码异常，携带 OCPP 错误码。 */
public class OcppException extends RuntimeException {

    private final String errorCode;

    public OcppException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OcppException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
