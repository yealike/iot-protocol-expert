package top.yealike.pep.control.knowledge.domain;

import java.util.List;

public record IngestReport(int total, int indexed, int skipped, int failed, List<IngestResult> items) {}
