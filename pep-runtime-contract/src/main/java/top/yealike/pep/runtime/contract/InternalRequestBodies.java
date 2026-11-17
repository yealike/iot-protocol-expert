package top.yealike.pep.runtime.contract;

import java.io.IOException;
import java.io.InputStream;

/** 内部接口读取请求体时的统一上限，避免未认证请求在 HMAC 校验前无限占用堆内存。 */
public final class InternalRequestBodies {

    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private InternalRequestBodies() {}

    public static byte[] read(InputStream input, long contentLength) throws IOException {
        if (contentLength > MAX_BYTES) {
            throw new TooLargeException();
        }
        byte[] body = input.readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw new TooLargeException();
        }
        return body;
    }

    public static final class TooLargeException extends IOException {
        public TooLargeException() {
            super("内部请求体超过 4 MiB 上限");
        }
    }
}
