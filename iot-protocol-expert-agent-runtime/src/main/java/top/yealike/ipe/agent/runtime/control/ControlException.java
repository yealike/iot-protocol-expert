package top.yealike.ipe.agent.runtime.control;

/** Control 返回的业务错误；消息已由 Control 归一化，不包含 SQL 或堆栈。 */
public class ControlException extends RuntimeException {

    private final int status;
    private final String code;

    public ControlException(int status, String code, String message) {
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
