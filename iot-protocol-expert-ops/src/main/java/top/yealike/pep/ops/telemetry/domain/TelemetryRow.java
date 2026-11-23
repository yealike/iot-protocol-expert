package top.yealike.pep.ops.telemetry.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;

public record TelemetryRow(
        Timestamp ts,
        String timeSource,
        BigDecimal voltage,
        BigDecimal current,
        BigDecimal power,
        BigDecimal gunTemp,
        BigDecimal batteryTemp,
        BigDecimal soc) {}
