package top.yealike.pep.ops.telemetry.domain;

import java.util.List;

public record TelemetryResult(List<TelemetryPoint> points, boolean truncated) {}
