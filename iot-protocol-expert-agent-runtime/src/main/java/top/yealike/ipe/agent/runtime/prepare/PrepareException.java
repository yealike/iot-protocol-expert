package top.yealike.ipe.agent.runtime.prepare;

/** 发布准备失败：带稳定错误码与 HTTP 语义。 */
public class PrepareException extends RuntimeException {

    private final int status;
    private final String code;

    public PrepareException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
