package top.yealike.ipe.ops.domain;

import java.time.LocalDateTime;

public class CommandRecord {
    public enum Status {
        PENDING,
        SENT,
        ACKED,
        WAITING_STATE,
        SUCCESS,
        REJECTED,
        TIMEOUT,
        UNKNOWN
    }

    public Long id;

    public Long deviceId;

    public String protocol;

    public String commandType;

    public String requestPayload;

    public Status status = Status.PENDING;

    public LocalDateTime sentAt;

    public LocalDateTime ackAt;

    public String responsePayload;

    public String operator;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
