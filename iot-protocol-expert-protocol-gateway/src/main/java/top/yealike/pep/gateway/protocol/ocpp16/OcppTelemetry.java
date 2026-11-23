package top.yealike.pep.gateway.protocol.ocpp16;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/** OCPP 消息级观测，与云快充的帧级观测并列。 */
@Component
public class OcppTelemetry {

    private final Tracer tracer;
    private final MeterRegistry meters;

    public OcppTelemetry(Tracer tracer, MeterRegistry meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    public TraceContext capture() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context();
    }

    public OcppTelemetryOperation start(
            String side, String operation, String action, String messageType, String uniqueId, TraceContext parent) {
        Span.Kind kind = "receive".equals(operation)
                ? Span.Kind.SERVER
                : "send".equals(operation) ? Span.Kind.CLIENT : Span.Kind.PRODUCER;
        Span span = tracer.spanBuilder()
                .name("ocpp16j " + operation + " " + (action == null ? messageType : action))
                .kind(kind)
                .tag("network.transport", "websocket")
                .tag("ipe.protocol", OcppProtocol.PROTOCOL_CODE)
                .tag("ipe.side", side)
                .tag("ipe.operation", operation)
                .tag("ipe.message.type", messageType == null ? "unknown" : messageType)
                .start();
        if (parent == null) {
            span.tag("ipe.parent", "none");
        } else {
            span.tag("ipe.parent", "remote");
        }
        return new OcppTelemetryOperation(tracer, meters, span, side, operation, action, messageType, uniqueId);
    }
}
