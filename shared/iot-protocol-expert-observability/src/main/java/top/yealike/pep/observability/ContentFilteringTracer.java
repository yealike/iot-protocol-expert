package top.yealike.pep.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 在 AgentScope 官方 TelemetryTracer 与 OTel SDK 之间做内容策略。
 *
 * <p>默认只保留 GenAI 元数据与 token 用量，丢弃 prompt / response / 工具参数正文；只有显式开启内容采集时才写入，
 * 并在写入前做常见凭据脱敏与长度截断，避免把完整业务数据或密钥带进 Trace。
 */
public final class ContentFilteringTracer implements Tracer {

    private static final Set<String> CONTENT_KEYS = Set.of(
            "gen_ai.input.messages",
            "gen_ai.output.messages",
            "gen_ai.system_instructions",
            "gen_ai.tool.call.arguments",
            "gen_ai.tool.call.result",
            "gen_ai.tool.definitions",
            "agentscope.function.input",
            "agentscope.function.output",
            "agentscope.format.output");

    private final Tracer delegate;
    private final boolean contentEnabled;
    private final int maxContentChars;

    public ContentFilteringTracer(Tracer delegate, boolean contentEnabled, int maxContentChars) {
        this.delegate = delegate;
        this.contentEnabled = contentEnabled;
        this.maxContentChars = Math.max(0, maxContentChars);
    }

    @Override
    public SpanBuilder spanBuilder(String spanName) {
        return new FilteringSpanBuilder(delegate.spanBuilder(spanName));
    }

    private boolean isContentKey(String key) {
        return CONTENT_KEYS.contains(key);
    }

    private String sanitize(String value) {
        return SensitiveDataSanitizer.sanitize(value, maxContentChars);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Attributes filterAttributes(Attributes attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return attributes;
        }
        var filtered = Attributes.builder();
        attributes.forEach((key, value) -> {
            Object safe = filterValue((AttributeKey) key, value);
            if (safe != null) {
                filtered.put((AttributeKey<Object>) key, safe);
            }
        });
        return filtered.build();
    }

    @SuppressWarnings("unchecked")
    private <T> T filterValue(AttributeKey<T> key, T value) {
        if (!isContentKey(key.getKey())) {
            return value;
        }
        if (!contentEnabled) {
            return null;
        }
        if (value instanceof String text) {
            return (T) sanitize(text);
        }
        return value;
    }

    private final class FilteringSpanBuilder implements SpanBuilder {

        private final SpanBuilder delegate;

        private FilteringSpanBuilder(SpanBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public SpanBuilder setParent(Context context) {
            delegate.setParent(context);
            return this;
        }

        @Override
        public SpanBuilder setNoParent() {
            delegate.setNoParent();
            return this;
        }

        @Override
        public SpanBuilder addLink(SpanContext spanContext) {
            delegate.addLink(spanContext);
            return this;
        }

        @Override
        public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
            delegate.addLink(spanContext, filterAttributes(attributes));
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, String value) {
            if (isContentKey(key)) {
                if (contentEnabled && value != null) {
                    delegate.setAttribute(key, sanitize(value));
                }
                return this;
            }
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, long value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, double value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, boolean value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
            T filtered = filterValue(key, value);
            if (filtered != null) {
                delegate.setAttribute(key, filtered);
            }
            return this;
        }

        @Override
        public SpanBuilder setSpanKind(SpanKind spanKind) {
            delegate.setSpanKind(spanKind);
            return this;
        }

        @Override
        public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
            delegate.setStartTimestamp(startTimestamp, unit);
            return this;
        }

        @Override
        public Span startSpan() {
            return new FilteringSpan(delegate.startSpan());
        }
    }

    private final class FilteringSpan implements Span {

        private final Span delegate;

        private FilteringSpan(Span delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            T filtered = filterValue(key, value);
            if (filtered != null) {
                delegate.setAttribute(key, filtered);
            }
            return this;
        }

        @Override
        public Span setAttribute(String key, String value) {
            if (isContentKey(key)) {
                if (contentEnabled && value != null) {
                    delegate.setAttribute(key, sanitize(value));
                }
                return this;
            }
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, long value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, double value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, boolean value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            delegate.addEvent(name, filterAttributes(attributes));
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            delegate.addEvent(name, filterAttributes(attributes), timestamp, unit);
            return this;
        }

        @Override
        public Span addLink(SpanContext spanContext) {
            delegate.addLink(spanContext);
            return this;
        }

        @Override
        public Span addLink(SpanContext spanContext, Attributes attributes) {
            delegate.addLink(spanContext, filterAttributes(attributes));
            return this;
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            delegate.setStatus(statusCode, description);
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            delegate.recordException(exception, filterAttributes(additionalAttributes));
            return this;
        }

        @Override
        public Span updateName(String name) {
            delegate.updateName(name);
            return this;
        }

        @Override
        public void end() {
            delegate.end();
        }

        @Override
        public void end(long timestamp, TimeUnit unit) {
            delegate.end(timestamp, unit);
        }

        @Override
        public SpanContext getSpanContext() {
            return delegate.getSpanContext();
        }

        @Override
        public boolean isRecording() {
            return delegate.isRecording();
        }

        @Override
        public Context storeInContext(Context context) {
            return delegate.storeInContext(context);
        }
    }
}
