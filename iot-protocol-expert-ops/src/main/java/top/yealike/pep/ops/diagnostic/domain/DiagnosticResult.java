package top.yealike.pep.ops.diagnostic.domain;

import java.util.List;
import java.util.Map;

public record DiagnosticResult(
        boolean ok,
        String error,
        Object device,
        Object decoded,
        Object order,
        List<Object> bills,
        List<DiagnosticStep> timeline,
        List<DiagnosticCitation> citations,
        List<String> pending,
        String lastEvidencedStep,
        String conclusion,
        boolean modelGenerated,
        String modelSummary,
        Map<String, Object> capabilities) {}
