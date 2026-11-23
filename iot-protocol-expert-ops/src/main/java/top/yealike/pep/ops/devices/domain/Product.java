package top.yealike.pep.ops.devices.domain;

import java.time.LocalDateTime;

/** 充电桩产品：绑定唯一协议，定义标准枪数和物模型。 */
public class Product {

    public Long id;
    public String productCode;
    public String name;
    public String categoryCode;
    public String protocol;
    public Long protocolDocumentId;
    public String transport = "NONE";
    public String vendor;
    public String model;
    public String metadata = "{}";
    public String configuration;
    public int connectorCount = 1;
    public String description;
    public String state = "PUBLISHED";
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
