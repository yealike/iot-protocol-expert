package top.yealike.pep.runtime.config.properties;

/** Runtime 观测配置。 */
public record ObservabilityProperties(OtelProperties otel) {

    public ObservabilityProperties {
        otel = otel == null ? OtelProperties.defaults() : otel;
    }

    static ObservabilityProperties defaults() {
        return new ObservabilityProperties(OtelProperties.defaults());
    }
}
