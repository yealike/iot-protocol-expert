package top.yealike.pep.modelgateway.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ApiException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> malformed(Exception exception) {
        return problem(400, "MODEL_REQUEST_INVALID", "请求格式或参数无效");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handle(IllegalArgumentException exception) {
        return problem(400, "MODEL_REQUEST_INVALID", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handle(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            String detail = error.getBody() == null ? null : error.getBody().getDetail();
            return problem(status, "HTTP_" + status, detail == null || detail.isBlank() ? "请求失败" : detail);
        }
        log.error("model gateway request failed", exception);
        return problem(500, "MODEL_GATEWAY_ERROR", "模型网关暂不可用");
    }

    private static ResponseEntity<ApiResponse<Void>> problem(int status, String code, String detail) {
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code, detail));
    }
}
