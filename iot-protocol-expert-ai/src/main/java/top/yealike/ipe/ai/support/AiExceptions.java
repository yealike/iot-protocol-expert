package top.yealike.ipe.ai.support;

/** AI 服务的业务异常：显式状态码与错误码，避免把内部细节透给前端。 */
public final class AiExceptions {

    private AiExceptions() {}

    public static class AiException extends RuntimeException {

        private final int status;
        private final String code;

        public AiException(int status, String code, String message) {
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

    public static AiException unauthorized(String message) {
        return new AiException(401, "UNAUTHORIZED", message);
    }

    public static AiException forbidden(String code, String message) {
        return new AiException(403, code, message);
    }

    public static AiException notFound(String message) {
        return new AiException(404, "NOT_FOUND", message);
    }

    public static AiException conflict(String code, String message) {
        return new AiException(409, code, message);
    }

    public static AiException badRequest(String message) {
        return new AiException(400, "BAD_REQUEST", message);
    }

    public static AiException upstream(String message) {
        return new AiException(502, "UPSTREAM_UNAVAILABLE", message);
    }

    public static AiException unavailable(String message) {
        return new AiException(503, "UNAVAILABLE", message);
    }
}
