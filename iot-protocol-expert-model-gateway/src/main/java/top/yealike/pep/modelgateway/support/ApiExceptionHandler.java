package top.yealike.pep.modelgateway.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.yealike.pep.modelgateway.support.dto.ApiError;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handle(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("MODEL_REQUEST_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handle(RuntimeException exception) {
        log.error("model gateway request failed", exception);
        return ResponseEntity.internalServerError().body(new ApiError("MODEL_GATEWAY_ERROR", "模型网关暂不可用"));
    }
}
