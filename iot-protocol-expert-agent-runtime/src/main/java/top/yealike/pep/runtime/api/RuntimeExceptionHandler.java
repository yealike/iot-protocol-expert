package top.yealike.pep.runtime.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.yealike.pep.observability.api.ApiResponse;
import top.yealike.pep.runtime.control.ControlException;
import top.yealike.pep.runtime.prepare.PrepareException;

/** Runtime 错误体：稳定 code + message，不回传堆栈、密钥或上游 SQL。 */
@RestControllerAdvice
public class RuntimeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeExceptionHandler.class);

    @ExceptionHandler(ControlException.class)
    public ResponseEntity<ApiResponse<Void>> control(ControlException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(PrepareException.class)
    public ResponseEntity<ApiResponse<Void>> prepare(PrepareException exception) {
        log.warn("release prepare rejected: code={} message={}", exception.code(), exception.getMessage());
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(RunRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(RunRejectedException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()));
        }
        return problem(exception.status(), exception.code(), exception.getMessage(), headers);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> malformed(Exception exception) {
        return problem(400, "BAD_REQUEST", "请求格式或参数无效");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException exception) {
        return problem(400, "BAD_REQUEST", message(exception));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            String detail = error.getBody() == null ? null : error.getBody().getDetail();
            return problem(status, "HTTP_" + status, detail == null || detail.isBlank() ? "请求失败" : detail);
        }
        log.error("agent runtime request failed", exception);
        return problem(500, "RUNTIME_ERROR", "执行服务暂时不可用");
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "请求参数无效" : message;
    }

    private static ResponseEntity<ApiResponse<Void>> problem(int status, String code, String detail) {
        return problem(status, code, detail, new HttpHeaders());
    }

    private static ResponseEntity<ApiResponse<Void>> problem(
            int status, String code, String detail, HttpHeaders headers) {
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code, detail));
    }
}
