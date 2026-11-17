package top.yealike.pep.runtime.api;

/** Run 接收拒绝；携带 HTTP 语义与可选 Retry-After。 */
public class RunRejectedException extends RuntimeException {

    private final int status;
    private final String code;
    private final Long retryAfterSeconds;

    public RunRejectedException(int status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
