package top.yealike.pep.ops.charging.domain;

import java.time.LocalDateTime;

/** 平台订单与协议侧交易号的映射，不在订单主表混用外部编号。 */
public class OrderExternalReference {
    public Long id;

    public Long orderId;

    public Long deviceId;

    public String protocol;

    public String referenceType;

    public String externalReference;

    public LocalDateTime createdAt;
}
