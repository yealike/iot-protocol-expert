package top.yealike.pep.control.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.yealike.pep.control.support.AiExceptions.AiException;
import top.yealike.pep.observability.api.ApiResponse;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(org.springframework.web.server.ResponseStatusException exception) {
        return failure(
                exception.getStatusCode(),
                "HTTP_" + exception.getStatusCode().value(),
                exception.getReason(),
                exception.getHeaders());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(
            org.springframework.security.access.AccessDeniedException exception) {
        return failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行此操作");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> integrity(
            org.springframework.dao.DataIntegrityViolationException exception) {
        log.warn("数据约束冲突", exception);
        return failure(HttpStatus.CONFLICT, "CONFLICT", "数据冲突，请刷新后重试");
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> malformed(Exception exception) {
        return failure(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求格式或参数无效");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException e) {
        return failure(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message(e));
    }

    @ExceptionHandler(AiException.class)
    public ResponseEntity<ApiResponse<Void>> ai(AiException exception) {
        return failure(HttpStatusCode.valueOf(exception.status()), exception.code(), message(exception));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> serverError(Exception e) {
        if (e instanceof ErrorResponse error) {
            HttpStatusCode status = error.getStatusCode();
            String detail = error.getBody() == null ? null : error.getBody().getDetail();
            return failure(status, "HTTP_" + status.value(), detail == null || detail.isBlank() ? "请求失败" : detail);
        }
        log.error("请求处理失败: {}", e.getMessage(), e);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
    }

    private static ResponseEntity<ApiResponse<Void>> failure(HttpStatusCode status, String code, String message) {
        return failure(status, code, message, new HttpHeaders());
    }

    private static ResponseEntity<ApiResponse<Void>> failure(
            HttpStatusCode status, String code, String message, HttpHeaders headers) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code, message));
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
