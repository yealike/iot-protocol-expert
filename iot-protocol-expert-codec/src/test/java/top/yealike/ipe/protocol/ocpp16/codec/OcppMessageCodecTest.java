package top.yealike.ipe.protocol.ocpp16.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OcppMessageCodecTest {

    @Test
    void decodesCallAndKeepsPayload() {
        OcppMessage message = OcppMessageCodec.decode(
                "[2,\"abc-1\",\"BootNotification\",{\"chargePointVendor\":\"IPE\",\"chargePointModel\":\"SIM\"}]");
        assertTrue(message.isCall());
        assertEquals("abc-1", message.uniqueId());
        assertEquals("BootNotification", message.action());
        assertEquals("IPE", message.payload().get("chargePointVendor").asString());
        assertEquals(
                "[2,\"abc-1\",\"BootNotification\",{\"chargePointVendor\":\"IPE\",\"chargePointModel\":\"SIM\"}]",
                OcppMessageCodec.encode(message));
    }

    @Test
    void decodesResultAndError() {
        OcppMessage result = OcppMessageCodec.decode("[3,\"id-1\",{\"status\":\"Accepted\"}]");
        assertTrue(result.isResult());
        assertEquals("Accepted", result.payload().get("status").asString());

        OcppMessage error = OcppMessageCodec.decode("[4,\"id-2\",\"NotImplemented\",\"unknown action\",{}]");
        assertTrue(error.isError());
        assertEquals("NotImplemented", error.errorCode());
        assertEquals("unknown action", error.errorDescription());
    }

    @Test
    void rejectsMalformedMessages() {
        assertThrows(OcppException.class, () -> OcppMessageCodec.decode("{}"));
        assertThrows(OcppException.class, () -> OcppMessageCodec.decode("[2]"));
        assertThrows(OcppException.class, () -> OcppMessageCodec.decode("[2,\"id\",\"Action\"]"));
        OcppException unsupported = assertThrows(OcppException.class, () -> OcppMessageCodec.decode("[1,\"id\"]"));
        assertEquals("MessageTypeNotSupported", unsupported.errorCode());
    }

    @Test
    void encodesCallResultWithEmptyPayload() {
        JsonNode payload = OcppMessageCodec.value(null);
        String encoded = OcppMessageCodec.encode(OcppMessage.result("u1", payload));
        assertEquals("[3,\"u1\",{}]", encoded);
    }
}
