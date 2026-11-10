package top.yealike.ipe.protocol.api;

import top.yealike.ipe.protocol.netty.SessionRegistry;
import top.yealike.ipe.protocol.service.DeviceService;
import top.yealike.ipe.protocol.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DeviceService.DeviceRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(DeviceService.DeviceRejectedException e) {
        return body(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(DeviceService.DeviceOfflineException.class)
    public ResponseEntity<Map<String, Object>> offline(DeviceService.DeviceOfflineException e) {
        return body(HttpStatus.CONFLICT, "DEVICE_OFFLINE", e.getMessage());
    }

    @ExceptionHandler(SessionRegistry.CommandTimeoutException.class)
    public ResponseEntity<Map<String, Object>> timeout(SessionRegistry.CommandTimeoutException e) {
        return body(HttpStatus.GATEWAY_TIMEOUT, "COMMAND_TIMEOUT", e.getMessage());
    }

    @ExceptionHandler({OrderService.OrderConflictException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class,top.yealike.ipe.protocol.codec.ProtocolException.class})
    public ResponseEntity<Map<String, Object>> invalid(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("接口异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("time", LocalDateTime.now());
        return ResponseEntity.status(status).body(body);
    }
}