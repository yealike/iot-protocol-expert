package top.yealike.pep.ops.diagnostic.domain;

import java.time.LocalDateTime;

public class TroubleCase {
    public Long id;

    public Long deviceId;

    public String orderNo;

    public String protocol;

    public String question;

    public String resultJson;

    public String createdBy;

    public LocalDateTime createdAt;
}
