package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 充电桩产品、设备和枪口的运维端契约。 */
public final class DeviceDtos {

    private DeviceDtos() {}

    // ------------------------------------------------------------------ 产品

    public record ProductRow(
            Long id,
            String productCode,
            String name,
            String categoryCode,
            String protocol,
            Long protocolDocumentId,
            String protocolEnglishName,
            String protocolVersion,
            String transport,
            String vendor,
            String model,
            int connectorCount,
            String description,
            String state,
            long deviceCount,
            int propertyCount,
            int functionCount,
            int eventCount) {}

    public record ProductDetail(ProductRow product, Map<String, Object> metadata, Map<String, Object> configuration) {}

    @Schema(description = "充电桩产品创建或更新请求")
    public record ProductSave(
            @Schema(description = "产品编码") String productCode,
            @Schema(description = "产品名称") String name,
            @Schema(description = "产品分类编码") String categoryCode,
            @Schema(description = "关联协议文档 ID") Long protocolDocumentId,
            @Schema(description = "通信传输方式") String transport,
            @Schema(description = "厂商") String vendor,
            @Schema(description = "型号") String model,
            @Schema(description = "标准枪数") Integer connectorCount,
            @Schema(description = "产品元数据") Map<String, Object> metadata,
            @Schema(description = "产品配置") Map<String, Object> configuration,
            @Schema(description = "产品说明") String description,
            @Schema(description = "产品状态") String state) {}

    // ------------------------------------------------------------------ 设备

    @Schema(description = "充电桩分页查询条件。8 位输入按设备 SN 精确查询，10 位输入按平台枪号查询。")
    public record DeviceQuery(
            @Schema(description = "8 位设备 SN、10 位平台枪号或设备名称") String keyword,
            @Schema(description = "设备状态列表") List<String> states,
            @Schema(description = "所属产品 ID") Long productId,
            @Schema(description = "是否启用") Boolean enabled,
            @Schema(description = "从 0 开始的页码") Integer page,
            @Schema(description = "每页记录数") Integer size,
            @Schema(description = "排序字段；仅支持接口白名单字段") String sort) {}

    public record DeviceRow(
            Long id,
            String deviceSn,
            String name,
            Long productId,
            String productName,
            String protocol,
            String protocolEnglishName,
            String protocolVersion,
            String state,
            boolean enabled,
            Map<String, Object> keyMetrics,
            String lastSeen,
            int gunCount,
            String matchedGunNo) {}

    public record ProtocolMappingInput(
            @Schema(description = "平台枪号，输入 1 至 99，保存时补齐为两位") String gunNo,

            @Schema(description = "第三方设备号；云快充桩号或 OCPP chargePointId")
            String thirdPartyDeviceId,

            @Schema(description = "第三方枪号，范围 1 至 99；留空时使用平台枪号")
            String protocolConnectorNo,

            @Schema(description = "是否启用；同一台充电桩的枪口配置必须使用同一值") Boolean enabled) {}

    public record ProtocolMappingView(
            String gunNo,
            String platformGunNo,
            String thirdPartyDeviceId,
            String protocolConnectorNo,
            boolean enabled) {}

    public record ProtocolMappingsSave(List<ProtocolMappingInput> mappings) {}

    @Schema(description = "模拟器使用的充电枪接入映射")
    public record SimulatorConnectorView(
            @Schema(description = "平台 10 位枪号，由 8 位设备 SN 和 2 位枪号组成")
            String platformGunNo,

            @Schema(description = "第三方协议中的充电枪编号") String protocolConnectorNo) {}

    @Schema(description = "模拟器设备目录项")
    public record SimulatorDeviceView(
            @Schema(description = "设备主键") Long id,
            @Schema(description = "8 位平台设备 SN") String sn,
            @Schema(description = "设备名称") String name,
            @Schema(description = "标准化协议编码") String protocol,
            @Schema(description = "第三方协议设备号；未配置时为空") String protocolDeviceId,
            @Schema(description = "产品标准枪数") int gunCount,
            @Schema(description = "充电桩是否在线") boolean online,
            @Schema(description = "设备状态") String state,
            @Schema(description = "设备是否启用") boolean enabled,
            @Schema(description = "设备是否允许模拟") boolean allowSimulate,
            @Schema(description = "已配置的充电枪接入映射") List<SimulatorConnectorView> connectors) {}

    @Schema(description = "充电桩创建或更新请求")
    public record DeviceSave(
            @Schema(description = "8 位设备 SN；创建时留空自动分配，创建后不可修改")
            String sn,

            @Schema(description = "设备名称") String name,
            @Schema(description = "充电桩产品 ID") Long productId,
            @Schema(description = "是否启用") Boolean enabled,
            @Schema(description = "按枪口配置的第三方设备映射") List<ProtocolMappingInput> protocolMappings) {}

    public record PropertyValue(
            String propertyId,
            String name,
            String channelNo,
            String valueType,
            Object value,
            String display,
            String unit,
            String updatedAt,
            boolean writable,
            boolean channelScoped) {}

    public record ChannelView(
            String gunNo,
            String platformGunNo,
            String protocolConnectorNo,
            String name,
            boolean enabled,
            List<PropertyValue> properties) {}

    public record EventView(
            Long id,
            String eventId,
            String eventName,
            String level,
            String channelNo,
            Object content,
            String occurredAt) {}

    public record CommandView(
            Long id,
            String commandType,
            String status,
            String requestPayload,
            String responsePayload,
            String operator,
            String createdAt,
            String sentAt,
            String ackAt) {}

    public record DeviceDetail(
            DeviceRow device,
            List<ChannelView> channels,
            List<PropertyValue> properties,
            List<EventView> events,
            List<CommandView> commands,
            List<ProtocolMappingView> protocolMappings) {}

    public record Overview(long deviceTotal, long deviceOnline, long deviceFault, long productCount) {}

    // ------------------------------------------------------------------ 协议映射加载

    @Schema(description = "Gateway 启动加载使用的充电枪映射")
    public record DeviceMappingConnector(String platformGunNo, String protocolConnectorNo) {}

    public record DeviceMappingRow(
            Long id,
            String deviceSn,
            String protocol,
            String thirdPartyDeviceId,
            boolean enabled,
            List<DeviceMappingConnector> connectors) {}

    public record DeviceMappingPage(
            List<DeviceMappingRow> content, boolean hasMore, Long nextAfterId, String snapshotToken) {}

    // ------------------------------------------------------------------ 数据

    public record PropertyWrite(
            @Schema(description = "物模型属性标识") String propertyId,
            @Schema(description = "枪号；非枪口属性可留空") String gunNo,
            @Schema(description = "要写入的属性值") Object value) {}

    public record PropertyBatchWrite(List<PropertyWrite> values, String collectedAt) {}

    public record SeriesPoint(String time, Double number, String text) {}

    public record SeriesView(
            Long deviceId, String propertyId, String gunNo, String name, String unit, List<SeriesPoint> points) {}

    public record EventReport(
            String eventId, String gunNo, String level, Map<String, Object> content, String occurredAt) {}
}
