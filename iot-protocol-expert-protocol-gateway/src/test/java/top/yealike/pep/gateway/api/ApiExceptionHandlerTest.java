package top.yealike.pep.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import top.yealike.pep.codec.ProtocolException;

class ApiExceptionHandlerTest {

    @Test
    void mapsMalformedProtocolFramesToBadRequest() {
        var response = new ApiExceptionHandler().malformedProtocol(new ProtocolException("帧长度不足 8 字节: 1"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("INVALID_PROTOCOL_FRAME", response.getBody().get("error"));
    }
}
