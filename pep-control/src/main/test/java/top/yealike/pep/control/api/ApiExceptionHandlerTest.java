package top.yealike.pep.control.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void preservesClientErrorsAndHidesInternalDetails() {
        assertEquals(
                400,
                handler.status(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid role"))
                        .getStatusCode()
                        .value());
        assertEquals(
                403,
                handler.denied(new AccessDeniedException("internal permission details"))
                        .getStatusCode()
                        .value());
        var error = handler.serverError(new IllegalStateException("jdbc:postgresql://private-host/password"));
        assertEquals(500, error.getStatusCode().value());
        assertFalse(error.getBody().toString().contains("private-host"));
    }
}
