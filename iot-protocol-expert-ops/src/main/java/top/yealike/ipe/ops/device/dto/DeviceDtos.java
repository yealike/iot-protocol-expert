package top.yealike.ipe.ops.device.dto;

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

    public record ProductSave(
            String productCode,
            String name,
            String categoryCode,
            String deviceType,
            String protocol,
            String transport,
            String vendor,
            String model,
            Map<String, Object> metadata,
            Map<String, Object> configuration,
            String sceneModel,
            String description,
            String state) {}

    /** 从已有产品复制物模型新建产品（不传 metadata 时使用模板）。 */
    public record ProductFromTemplate(String templateProductId, String productCode, String name, String description) {}

    // ------------------------------------------------------------------ 设备

    public record DeviceQuery(
            String keyword,
            String deviceType,
            List<String> states,
            Long stationId,
            Long productId,
            Boolean enabled,
            Map<String, String> tagFilters,
            Integer page,
            Integer size,
            String sort) {}

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

    public record DeviceSave(
            String deviceCode,
            String name,
            Long productId,
            String protocolDeviceId,
            Long stationId,
            Long parentId,
            Boolean enabled,
            Boolean allowSimulate,
            Boolean allowWork,
            String remark,
            SceneAnchor sceneAnchor,
            Map<String, String> tags,
            List<ChannelSpec> channels,
            Map<String, Object> configuration) {}

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

    public record PropertyWrite(String propertyId, String channelNo, Object value) {}

    public record PropertyBatchWrite(List<PropertyWrite> values, String collectedAt) {}

    public record SeriesPoint(String time, Double number, String text) {}

    public record SeriesView(
            Long deviceId, String propertyId, String channelNo, String name, String unit, List<SeriesPoint> points) {}

    public record EventReport(
            String eventId, String channelNo, String level, Map<String, Object> content, String occurredAt) {}
}
