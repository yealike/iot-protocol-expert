package top.yealike.pep.ops.charging.domain;

import java.util.List;

public record RateSnapshot(String modelNo, int lossRatio, List<Integer> timeSegments, List<SegmentRate> rates) {}
