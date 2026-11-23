package top.yealike.pep.ops.diagnostic.domain;

import java.time.LocalDateTime;

public record DiagnosticStep(String step, String status, LocalDateTime time, String evidence, String detail) {}
