package top.yealike.pep.runtime.contract;

import java.util.List;

public record RunEventAck(List<Long> acceptedSourceSeq, long lastCursor) {

    public RunEventAck {
        acceptedSourceSeq = acceptedSourceSeq == null ? List.of() : List.copyOf(acceptedSourceSeq);
    }
}
