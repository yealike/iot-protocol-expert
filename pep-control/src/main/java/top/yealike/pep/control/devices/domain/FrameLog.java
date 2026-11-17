package top.yealike.pep.control.devices.domain;

import java.time.LocalDateTime;

public class FrameLog {
    public Long id;

    public Long deviceId;

    public String protocol;

    public String protocolDeviceId;

    public String direction;

    public int frameType;

    public String frameLabel;

    public String messageId;

    public int sequenceNo;

    public boolean encrypted;

    public String bodyHex;

    public String wireHex;

    public String summary;

    public String errorMessage;

    public String traceId;

    public LocalDateTime createdAt;
}
