package top.yealike.pep.runtime.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.yealike.pep.runtime.api.dto.ApiError;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.prepare.PrepareException;

/** Runtime 错误体：稳定 code + message，不回传堆栈、密钥或上游 SQL。 */
@RestControllerAdvice
public class RuntimeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeExceptionHandler.class);

    @ExceptionHandler(ControlException.class)
    public ResponseEntity<ApiError> control(ControlException exception) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(PrepareException.class)
    public ResponseEntity<ApiError> prepare(PrepareException exception) {
        log.warn("release prepare rejected: code={} message={}", exception.code(), exception.getMessage());
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(RunRejectedException.class)
    public ResponseEntity<ApiError> rejected(RunRejectedException exception) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()));
        }
        return builder.body(error(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiError> malformed(Exception exception) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", "请求格式或参数无效"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", message(exception)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("agent runtime request failed", exception);
        return ResponseEntity.internalServerError().body(error("RUNTIME_ERROR", "执行服务暂时不可用"));
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "请求参数无效" : message;
    }

    private static ApiError error(String code, String message) {
        return new ApiError(code, message == null ? "请求失败" : message);
    }
}
