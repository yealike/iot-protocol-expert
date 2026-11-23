package top.yealike.pep.gateway.protocol.ocpp16;

import java.util.concurrent.TimeoutException;
import top.yealike.pep.observability.protocol.ProtocolTelemetry;

/** OCPP 下行命令失败。 */
public class OcppCommandException extends RuntimeException implements ProtocolTelemetry.TimeoutFailure {
    private final boolean timeout;

    public OcppCommandException(String message) {
        super(message);
        this.timeout = false;
    }

    public OcppCommandException(String message, Throwable cause) {
        super(message, cause);
        this.timeout = cause instanceof TimeoutException;
    }

    @Override
    public boolean isTimeout() {
        return timeout;
    }
}
