package top.yealike.pep.simulator.runtime;

import java.math.BigDecimal;

/** 等待上报的 OCPP StopTransaction。 */
final class PendingOcppStop {
    final String gunNo;
    final int transactionId;
    final BigDecimal meterStopWh;
    final String endedAt;
    final int reason;
    int retriesLeft;
    long nextAt;

    PendingOcppStop(
            String gunNo,
            int transactionId,
            BigDecimal meterStopWh,
            String endedAt,
            int reason,
            int retriesLeft,
            long nextAt) {
        this.gunNo = gunNo;
        this.transactionId = transactionId;
        this.meterStopWh = meterStopWh;
        this.endedAt = endedAt;
        this.reason = reason;
        this.retriesLeft = retriesLeft;
        this.nextAt = nextAt;
    }
}
