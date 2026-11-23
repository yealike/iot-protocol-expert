package top.yealike.pep.simulator.runtime;

import top.yealike.pep.codec.frames.OperationFrames;

/** 等待上报的云快充交易记录。 */
final class PendingTransaction {
    final OperationFrames.Transaction transaction;
    int retriesLeft;
    long nextAt;

    PendingTransaction(OperationFrames.Transaction transaction, int retriesLeft, long nextAt) {
        this.transaction = transaction;
        this.retriesLeft = retriesLeft;
        this.nextAt = nextAt;
    }
}
