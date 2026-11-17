package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 设备域对外契约。
 *
 * <p>刻意与实体分离：实体会随存储演进（比如 JSON 列换格式），DTO 是前端契约， 不应跟着动。改造前 {@code DeviceController.row()} 手拼 {@code
 * LinkedHashMap}， 字段名没有编译期保证，这是本次重构重点修正的地方。
 */
public final class DeviceDtos {

    private DeviceDtos() {}

    // ------------------------------------------------------------------ 产品

    public record ProductRow(
            Long id,
            String productCode,
            String name,
            String categoryCode,
            String deviceType,
            String deviceTypeLabel,
            String protocol,
            Long protocolDocumentId,
            String protocolName,
            String protocolNameEn,
            String protocolVersion,
            String transport,
            String vendor,
            String model,
            String sceneModel,
            String description,
            String state,
            long deviceCount,
            int propertyCount,
            int functionCount,
            int eventCount) {}

    public record ProductDetail(ProductRow product, Map<String, Object> metadata, Map<String, Object> configuration) {}

    @Schema(description = "产品档案创建或更新请求")
    public record ProductSave(
            @Schema(description = "产品编码") String productCode,
            @Schema(description = "产品名称") String name,
            @Schema(description = "产品分类编码") String categoryCode,
            @Schema(description = "设备类型编码") String deviceType,
            @Schema(description = "协议编码") String protocol,
            @Schema(description = "关联协议文档 ID") Long protocolDocumentId,
            @Schema(description = "通信传输方式") String transport,
            @Schema(description = "厂商") String vendor,
            @Schema(description = "型号") String model,
            @Schema(description = "产品元数据") Map<String, Object> metadata,
            @Schema(description = "产品配置") Map<String, Object> configuration,
            @Schema(description = "三维场景模型标识") String sceneModel,
            @Schema(description = "产品说明") String description,
            @Schema(description = "产品状态") String state) {}

    /** 从已有产品复制物模型新建产品（不传 metadata 时使用模板）。 */
    public record ProductFromTemplate(String templateProductId, String productCode, String name, String description) {}

    // ------------------------------------------------------------------ 设备

    @Schema(description = "设备分页查询条件")
    public record DeviceQuery(
            @Schema(description = "设备名称或编码关键字") String keyword,
            @Schema(description = "设备类型编码") String deviceType,
            @Schema(description = "设备状态列表") List<String> states,
            @Schema(description = "所属站点 ID") Long stationId,
            @Schema(description = "所属产品 ID") Long productId,
            @Schema(description = "是否启用") Boolean enabled,
            @Schema(description = "标签筛选条件") Map<String, String> tagFilters,
            @Schema(description = "从 0 开始的页码") Integer page,
            @Schema(description = "每页记录数") Integer size,
            @Schema(description = "排序字段；仅支持接口白名单字段") String sort) {}

    public record TagValue(String key, String value, String valueType) {}

    public record SceneAnchor(String model, List<Double> position, Double scale) {}

    public record DeviceRow(
            Long id,
            String deviceCode,
            String name,
            Long productId,
            String productName,
            String deviceType,
            String deviceTypeLabel,
            String protocol,
            String protocolName,
            String protocolNameEn,
            String protocolVersion,
            String protocolDeviceId,
            Long stationId,
            String stationName,
            String state,
            boolean enabled,
            boolean allowWork,
            boolean allowSimulate,
            String lastSeen,
            String onlineAt,
            String registryTime,
            SceneAnchor sceneAnchor,
            List<TagValue> tags,
            Map<String, Object> highlights,
            int gunCount,
            int channelCount) {}

    public record ChannelSpec(String channelNo, String channelType, String name, Boolean enabled) {}

    @Schema(description = "设备档案创建或更新请求")
    public record DeviceSave(
            @Schema(description = "设备编码") String deviceCode,
            @Schema(description = "设备名称") String name,
            @Schema(description = "产品 ID") Long productId,
            @Schema(description = "协议侧设备编号") String protocolDeviceId,
            @Schema(description = "所属站点 ID") Long stationId,
            @Schema(description = "父设备 ID") Long parentId,
            @Schema(description = "是否启用") Boolean enabled,
            @Schema(description = "是否允许模拟") Boolean allowSimulate,
            @Schema(description = "是否允许运行") Boolean allowWork,
            @Schema(description = "备注") String remark,
            @Schema(description = "三维场景锚点") SceneAnchor sceneAnchor,
            @Schema(description = "设备标签") Map<String, String> tags,
            @Schema(description = "设备通道配置") List<ChannelSpec> channels,
            @Schema(description = "设备扩展配置") Map<String, Object> configuration) {}

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
            String channelNo, String channelType, String name, boolean enabled, List<PropertyValue> properties) {}

    public record EventView(
            Long id,
            String eventId,
            String eventName,
            String level,
            String channelNo,
            Object content,
            String occurredAt) {}

    public record AlarmView(
            Long id,
            Long deviceId,
            String deviceCode,
            String deviceName,
            String channelNo,
            String level,
            String title,
            String state,
            String firstAt,
            String lastAt,
            String handledBy,
            String handledAt,
            String handlingNote) {}

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
            Map<String, Object> thingModel,
            List<ChannelView> channels,
            List<PropertyValue> properties,
            List<EventView> events,
            List<AlarmView> alarms,
            List<CommandView> commands) {}

    /** 首页/大屏总览：按设备类型聚合。 */
    public record DeviceTypeSummary(
            String deviceType, String label, long total, long online, long fault, long offline, long alarmOpen) {}

    public record Overview(
            long deviceTotal,
            long deviceOnline,
            long deviceFault,
            long alarmOpen,
            long stationCount,
            long productCount,
            List<DeviceTypeSummary> byType,
            List<AlarmView> latestAlarms) {}

    // ------------------------------------------------------------------ 数据

    public record PropertyWrite(
            @Schema(description = "物模型属性标识") String propertyId,
            @Schema(description = "通道编号；非通道属性可留空") String channelNo,
            @Schema(description = "要写入的属性值") Object value) {}

    public record PropertyBatchWrite(List<PropertyWrite> values, String collectedAt) {}

    public record SeriesPoint(String time, Double number, String text) {}

    public record SeriesView(
            Long deviceId, String propertyId, String channelNo, String name, String unit, List<SeriesPoint> points) {}

    public record EventReport(
            String eventId, String channelNo, String level, Map<String, Object> content, String occurredAt) {}
}
