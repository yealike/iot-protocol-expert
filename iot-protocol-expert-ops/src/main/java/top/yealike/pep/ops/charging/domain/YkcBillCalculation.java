package top.yealike.pep.ops.charging.domain;

import java.util.List;

public record YkcBillCalculation(FeeTotal total, List<ChargeOrderSegment> segments) {}
