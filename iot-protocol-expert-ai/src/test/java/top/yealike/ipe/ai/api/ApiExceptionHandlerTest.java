package top.yealike.ipe.ai.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
                handler.handleBadRequest(new IllegalArgumentException())
                        .getStatusCode()
                        .value());
    }
}
