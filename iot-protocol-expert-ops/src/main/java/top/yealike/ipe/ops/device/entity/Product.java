package top.yealike.ipe.ops.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import top.yealike.ipe.ops.support.JsonbStringType;

/**
 * 产品：一类设备的模板。承载物模型（metadata）与协议接入方式。
 *
 * <p>产品与设备实例是「模板 + 覆盖」关系。改产品物模型只影响继承它的设备， 已经写过 {@code derive_metadata} 的设备不受影响。
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "product_code", nullable = false, unique = true, length = 64)
    public String productCode;

    @Column(nullable = false, length = 128)
    public String name;

    @Column(name = "category_code", nullable = false, length = 64)
    public String categoryCode;

    /** CHARGER / PV / WIND / STORAGE / LOCK / GATE / CAMERA / METER，见 DeviceTypeCatalog。 */
    @Column(name = "device_type", nullable = false, length = 32)
    public String deviceType;

    @Column(nullable = false, length = 32)
    public String protocol;

    @Column(name = "protocol_document_id")
    public Long protocolDocumentId;

    /** TCP / WS / MQTT / HTTP / NONE。 */
    @Column(nullable = false, length = 16)
    public String transport = "NONE";

    @Column(length = 64)
    public String vendor;

    @Column(length = 64)
    public String model;

    /** 物模型 JSON。 */
    @Type(JsonbStringType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String metadata = "{}";

    /** 协议默认配置（心跳、超时、寄存器映射等），设备级 configuration 同名键覆盖。 */
    @Type(JsonbStringType.class)
    @Column(columnDefinition = "jsonb")
    public String configuration;

    /** 3D 场景默认节点名。 */
    @Column(name = "scene_model", length = 64)
    public String sceneModel;

    @Column(length = 512)
    public String description;

    /** DRAFT / PUBLISHED。草稿产品不能被设备引用。 */
    @Column(nullable = false, length = 16)
    public String state = "PUBLISHED";

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
