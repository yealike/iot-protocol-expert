package top.yealike.pep.ops.telemetry.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record TelemetryPoint(
        Instant time,
        String timeSource,
        BigDecimal voltage,
        BigDecimal current,
        BigDecimal power,
        BigDecimal gunTemperature,
        BigDecimal batteryTemperature,
        BigDecimal soc) {}
