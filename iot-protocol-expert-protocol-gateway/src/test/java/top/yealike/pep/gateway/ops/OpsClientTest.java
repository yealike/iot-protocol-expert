package top.yealike.pep.gateway.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import top.yealike.pep.gateway.config.ProtocolProperties;

class OpsClientTest {

    @Test
    void rejectedFrameRetentionNeverEscapesIntoProtocolHandling() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        OpsClient client = new OpsClient(RestClient.builder(), new ProtocolProperties(), rejecting, meters);

        assertDoesNotThrow(() -> client.frame(new OpsDtos.FrameRequest(
                "OCPP16J",
                "pile",
                "IN",
                2,
                "CALL",
                "1",
                0,
                false,
                "[]",
                null,
                "test",
                null,
                null,
                LocalDateTime.now())));

        assertEquals(
                1.0,
                meters.get("ipe.protocol.frame.retention.dropped").counter().count());
    }
}
