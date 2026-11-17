package top.yealike.pep.control.api.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.pep.control.api.ApiExceptionHandler;

class ApiExceptionHandlerTest {
    @Test
    void streamCapacityErrorsStay429AndNullArgumentMessagesAreSafe() {
        var handler = new ApiExceptionHandler();
        assertEquals(
                429,
                handler.status(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS))
                        .getStatusCode()
                        .value());
        assertEquals(
                400,
                handler.badRequest(new IllegalArgumentException())
                        .getStatusCode()
                        .value());
    }
}
