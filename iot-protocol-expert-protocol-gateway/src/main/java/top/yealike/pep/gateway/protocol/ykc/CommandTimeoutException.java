package top.yealike.pep.gateway.protocol.ykc;

import java.util.concurrent.TimeoutException;
import top.yealike.pep.observability.protocol.ProtocolTelemetry;

/** 云快充下行命令超时或中断。 */
public class CommandTimeoutException extends RuntimeException implements ProtocolTelemetry.TimeoutFailure {
    private final boolean timeout;

    public CommandTimeoutException(String message) {
        super(message);
        timeout = true;
    }

    public CommandTimeoutException(String message, Throwable cause) {
        super(message, cause);
        timeout = cause instanceof TimeoutException;
    }

    @Override
    public boolean isTimeout() {
        return timeout;
    }
}
