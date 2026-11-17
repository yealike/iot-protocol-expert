package top.yealike.pep.ops.devices.entity;

import java.time.LocalDateTime;

/**
 * 产品：一类设备的模板。承载物模型（metadata）与协议接入方式。
 *
 * <p>产品与设备实例是「模板 + 覆盖」关系。改产品物模型只影响继承它的设备， 已经写过 {@code derive_metadata} 的设备不受影响。
 */
public class Product {

    public Long id;

    public String productCode;

    public String name;

    public String categoryCode;

    /** CHARGER / PV / WIND / STORAGE / LOCK / GATE / CAMERA / METER，见 DeviceTypeCatalog。 */
    public String deviceType;

    public String protocol;

    public Long protocolDocumentId;

    /** TCP / WS / MQTT / HTTP / NONE。 */
    public String transport = "NONE";

    public String vendor;

    public String model;

    /** 物模型 JSON。 */
    public String metadata = "{}";

    /** 协议默认配置（心跳、超时、寄存器映射等），设备级 configuration 同名键覆盖。 */
    public String configuration;

    /** 3D 场景默认节点名。 */
    public String sceneModel;

    public String description;

    /** DRAFT / PUBLISHED。草稿产品不能被设备引用。 */
    public String state = "PUBLISHED";

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
