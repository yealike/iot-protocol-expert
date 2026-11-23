package top.yealike.pep.ops.telemetry.domain;

import java.math.BigDecimal;
import java.sql.Timestamp;

public record TelemetrySample(
        long id,
        String orderNo,
        Timestamp time,
        String source,
        BigDecimal voltage,
        BigDecimal current,
        BigDecimal power,
        BigDecimal gunTemp,
        BigDecimal batteryTemp,
        BigDecimal soc) {}
