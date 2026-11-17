package top.yealike.pep.ops.devices.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.alarms.service.AlarmService;
import top.yealike.pep.ops.charging.domain.CommandRecord;
import top.yealike.pep.ops.charging.domain.Station;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.charging.mapper.StationMapper;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.DeviceChannel;
import top.yealike.pep.ops.devices.entity.DeviceEventLog;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DevicePropertyLatest;
import top.yealike.pep.ops.devices.entity.DeviceTag;
import top.yealike.pep.ops.devices.entity.Product;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.ops.devices.mapper.DeviceTagMapper;
import top.yealike.pep.ops.devices.thingmodel.DeviceTypeCatalog;
import top.yealike.pep.ops.devices.thingmodel.ThingModel;
import top.yealike.pep.ops.devices.thingmodel.ThingModelCodec;
import top.yealike.pep.ops.support.page.PageResult;
import top.yealike.pep.ops.support.page.PageSupport;

/**
 * 设备实例服务：档案、生命周期与视图组装。
 *
 * <p>与改造前的 {@code DeviceService} 相比，职责边界清楚了：
 *
 * <ul>
 *   <li>档案与状态在这里；
 *   <li>实时值在 {@link DevicePropertyService}；
 *   <li>物模型在 {@link ThingModelResolver}；
 *   <li>告警在 {@link AlarmService}。
 * </ul>
 *
 * 原来一个 280 行的类同时做这四件事，是「粗制滥造」的主要来源。
 */
@Service
public class DeviceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceInstanceService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SORTABLE =
            Set.of("id", "deviceCode", "name", "deviceType", "protocol", "state", "lastSeen", "createdAt", "updatedAt");

    private final DeviceInstanceMapper devices;
    private final DeviceTagMapper tags;
    private final DeviceChannelMapper channels;
    private final DeviceEventLogMapper events;
    private final DevicePropertyLatestMapper latest;
    private final ProductService products;
    private final StationMapper stations;
    private final ThingModelResolver models;
    private final DevicePropertyService properties;
    private final AlarmService alarms;
    private final CommandRecordMapper commands;

    public DeviceInstanceService(
            DeviceInstanceMapper devices,
            DeviceTagMapper tags,
            DeviceChannelMapper channels,
            DeviceEventLogMapper events,
            DevicePropertyLatestMapper latest,
            ProductService products,
            StationMapper stations,
            ThingModelResolver models,
            DevicePropertyService properties,
            AlarmService alarms,
            CommandRecordMapper commands) {
        this.devices = devices;
        this.tags = tags;
        this.channels = channels;
        this.events = events;
        this.latest = latest;
        this.products = products;
        this.stations = stations;
        this.models = models;
        this.properties = properties;
        this.alarms = alarms;
        this.commands = commands;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<DeviceDtos.DeviceRow> query(DeviceDtos.DeviceQuery query) {
        String keyword = query.keyword() == null || query.keyword().isBlank()
                ? null
                : query.keyword().trim();
        String deviceType = query.deviceType() == null || query.deviceType().isBlank()
                ? null
                : query.deviceType().trim().toUpperCase(Locale.ROOT);
        List<String> states = query.states() == null
                ? null
                : query.states().stream().map(DeviceInstance::normalizeState).toList();
        Map<String, String> tagFilters = query.tagFilters() == null ? Map.of() : query.tagFilters();
        PageSupport.PageQuery paging = PageSupport.pageable(query.page(), query.size(), query.sort(), SORTABLE);
        List<DeviceInstance> content = devices.search(
                keyword,
                deviceType,
                states,
                query.stationId(),
                query.productId(),
                query.enabled(),
                tagFilters,
                paging.orderBy(),
                paging.size(),
                paging.offset());
        long total = devices.countSearch(
                keyword, deviceType, states, query.stationId(), query.productId(), query.enabled(), tagFilters);
        ViewContext context = context(content);
        return PageResult.of(content, paging.page(), paging.size(), total, device -> row(device, context));
    }

    public DeviceInstance get(Long id) {
        return devices.findById(id).orElseThrow(() -> new IllegalArgumentException("设备不存在: " + id));
    }

    public Optional<DeviceInstance> findByCode(String deviceCode) {
        return devices.findByDeviceCode(deviceCode);
    }

    public DeviceInstance resolve(String protocol, String protocolDeviceId) {
        return devices.findByProtocolAndProtocolDeviceId(normalizeProtocol(protocol), protocolDeviceId)
                .orElseThrow(() -> new DeviceRejectedException("设备未登记: " + protocol + "/" + protocolDeviceId));
    }

    public List<DeviceInstance> simulatorDevices() {
        return devices.findAll().stream()
                .filter(device -> "CHARGER".equals(device.deviceType) && device.enabled && device.allowSimulate)
                .sorted(Comparator.comparing(device -> device.id))
                .toList();
    }

    // ------------------------------------------------------------------ 详情

    public DeviceDtos.DeviceDetail detail(Long id) {
        DeviceInstance device = get(id);
        ThingModel model = models.of(device);
        ViewContext context = context(List.of(device));
        return new DeviceDtos.DeviceDetail(
                row(device, context),
                ThingModelCodec.toMap(model),
                properties.channelViews(device, model),
                properties.propertyViews(device, model),
                eventViews(device.id),
                alarms.openFor(device.id),
                commandViews(device.id));
    }

    private List<DeviceDtos.EventView> eventViews(Long deviceId) {
        ThingModel model = models.of(deviceId);
        List<DeviceDtos.EventView> views = new ArrayList<>();
        for (DeviceEventLog event : events.findTop50ByDeviceIdOrderByOccurredAtDesc(deviceId)) {
            ThingModel.EventMetadata metadata = model.event(event.eventId).orElse(null);
            views.add(new DeviceDtos.EventView(
                    event.id,
                    event.eventId,
                    metadata == null ? event.eventId : metadata.name(),
                    event.level,
                    event.channelNo,
                    parse(event.content),
                    event.occurredAt == null ? null : event.occurredAt.format(TIME)));
        }
        return views;
    }

    private List<DeviceDtos.CommandView> commandViews(Long deviceId) {
        List<DeviceDtos.CommandView> views = new ArrayList<>();
        for (CommandRecord record : commands.findTop100ByDeviceIdOrderByIdDesc(deviceId)) {
            views.add(new DeviceDtos.CommandView(
                    record.id,
                    record.commandType,
                    record.status == null ? null : record.status.name(),
                    record.requestPayload,
                    record.responsePayload,
                    record.operator,
                    format(record.createdAt),
                    format(record.sentAt),
                    format(record.ackAt)));
            if (views.size() >= 20) {
                break;
            }
        }
        return views;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME);
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return ThingModelCodec.mapper().readValue(json, Object.class);
        } catch (RuntimeException e) {
            return json;
        }
    }

    // ------------------------------------------------------------------ 视图组装

    /** 列表页的批量上下文，避免每行都查一次站点与属性。 */
    private record ViewContext(
            Map<Long, String> stationNames,
            Map<Long, String> productNames,
            Map<Long, Map<String, Object>> protocolDocuments,
            Map<Long, List<DeviceTag>> tags,
            Map<Long, Map<String, DevicePropertyLatest>> properties,
            Map<Long, int[]> channelCounts) {}

    private ViewContext context(List<DeviceInstance> list) {
        Map<Long, String> stationNames = new LinkedHashMap<>();
        Map<Long, String> productNames = new LinkedHashMap<>();
        Map<Long, List<DeviceTag>> tagMap = new LinkedHashMap<>();
        Map<Long, Map<String, DevicePropertyLatest>> propertyMap = new LinkedHashMap<>();
        Map<Long, int[]> channelCounts = new LinkedHashMap<>();
        if (list.isEmpty()) {
            return new ViewContext(stationNames, productNames, Map.of(), tagMap, propertyMap, channelCounts);
        }
        Set<Long> stationIds = new LinkedHashSet<>();
        Set<Long> productIds = new LinkedHashSet<>();
        for (DeviceInstance device : list) {
            if (device.stationId != null) {
                stationIds.add(device.stationId);
            }
            if (device.productId != null) {
                productIds.add(device.productId);
            }
        }
        if (!stationIds.isEmpty()) {
            for (Station station : stations.findAllById(stationIds)) {
                stationNames.put(station.id, station.name);
            }
        }
        if (!productIds.isEmpty()) {
            for (Product product : products.findAllById(productIds)) {
                productNames.put(product.id, product.name);
            }
        }
        List<Long> deviceIds = list.stream().map(device -> device.id).toList();
        // 批量取，避免「每台设备 1 次标签查询 + 1 次通道查询」的 N+1
        for (DeviceTag tag : tags.findByDeviceIdInOrderByDeviceIdAsc(deviceIds)) {
            tagMap.computeIfAbsent(tag.deviceId, key -> new ArrayList<>()).add(tag);
        }
        for (DevicePropertyLatest row : latest.findByDeviceIdIn(deviceIds)) {
            propertyMap
                    .computeIfAbsent(row.deviceId, key -> new LinkedHashMap<>())
                    .put(row.channelNo + "|" + row.propertyId, row);
        }
        for (DeviceChannel channel : channels.findByDeviceIdIn(deviceIds)) {
            int[] counts = channelCounts.computeIfAbsent(channel.deviceId, key -> new int[2]);
            counts[1]++;
            if ("CONNECTOR".equals(channel.channelType)) {
                counts[0]++;
            }
        }
        return new ViewContext(
                stationNames, productNames, products.protocolDocuments(productIds), tagMap, propertyMap, channelCounts);
    }

    public DeviceDtos.DeviceRow view(DeviceInstance device) {
        return row(device, context(List.of(device)));
    }

    /** 批量视图：一次查完站点、产品、标签、属性、通道计数。 */
    public List<DeviceDtos.DeviceRow> rows(List<DeviceInstance> devices) {
        if (devices.isEmpty()) {
            return List.of();
        }
        ViewContext context = context(devices);
        return devices.stream().map(device -> row(device, context)).toList();
    }

    private DeviceDtos.DeviceRow row(DeviceInstance device, ViewContext context) {
        ThingModel model = models.of(device);
        List<DeviceTag> deviceTags = context.tags().get(device.id);
        if (deviceTags == null) {
            deviceTags = List.of();
        }
        List<DeviceDtos.TagValue> tagValues = deviceTags.stream()
                .map(tag -> new DeviceDtos.TagValue(tag.tagKey, tag.tagValue, tag.valueType))
                .toList();
        // 注意：不能用 getOrDefault(..., 查询)，默认值参数会被提前求值，等于白白多一次查询
        Map<String, DevicePropertyLatest> stored = context.properties().get(device.id);
        if (stored == null) {
            stored = properties.latestMap(device.id);
        }

        Map<String, Object> protocolDoc = context.protocolDocuments().getOrDefault(device.productId, Map.of());
        return new DeviceDtos.DeviceRow(
                device.id,
                device.deviceCode,
                device.name,
                device.productId,
                context.productNames().get(device.productId),
                device.deviceType,
                DeviceTypeCatalog.find(device.deviceType)
                        .map(DeviceTypeCatalog::label)
                        .orElse(device.deviceType),
                device.protocol,
                (String) protocolDoc.get("protocol_code"),
                (String) protocolDoc.get("protocol_name_en"),
                (String) protocolDoc.get("document_version"),
                device.protocolDeviceId,
                device.stationId,
                context.stationNames().get(device.stationId),
                device.getStateOrOffline(),
                device.enabled,
                device.allowWork,
                device.allowSimulate,
                format(device.lastSeen),
                format(device.onlineAt),
                format(device.registryTime),
                anchor(device),
                tagValues,
                highlights(model, stored),
                channelCounts(context, device.id)[0],
                channelCounts(context, device.id)[1]);
    }

    private static int[] channelCounts(ViewContext context, Long deviceId) {
        int[] counts = context.channelCounts().get(deviceId);
        return counts == null ? new int[] {0, 0} : counts;
    }

    /** 列表页展示的关键指标：物模型里前 4 个有值的只读属性，带单位。 */
    private Map<String, Object> highlights(ThingModel model, Map<String, DevicePropertyLatest> stored) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ThingModel.PropertyMetadata property : model.properties()) {
            if (property.channel() || property.id().equals("allowWork")) {
                continue;
            }
            DevicePropertyLatest row = stored.get("|" + property.id());
            if (row == null) {
                continue;
            }
            var value = new top.yealike.pep.ops.devices.thingmodel.ThingValues.StoredValue(
                    row.valueType, row.valueNum, row.valueText, row.valueJson, row.valueTime);
            String display = top.yealike.pep.ops.devices.thingmodel.ThingValues.format(property.valueType(), value);
            if (display == null) {
                continue;
            }
            String unit = property.valueType() == null || property.valueType().unit() == null
                    ? ""
                    : " " + property.valueType().unit();
            result.put(property.id(), display + unit);
            if (result.size() >= 4) {
                break;
            }
        }
        return result;
    }

    private DeviceDtos.SceneAnchor anchor(DeviceInstance device) {
        if (device.sceneAnchor == null || device.sceneAnchor.isBlank()) {
            String fallback = DeviceTypeCatalog.find(device.deviceType)
                    .map(DeviceTypeCatalog::sceneModel)
                    .orElse(null);
            return fallback == null ? null : new DeviceDtos.SceneAnchor(fallback, null, null);
        }
        try {
            Map<String, Object> map = ThingModelCodec.mapper().readValue(device.sceneAnchor, Map.class);
            String model = map.get("model") == null ? null : String.valueOf(map.get("model"));
            List<Double> position = new ArrayList<>();
            if (map.get("position") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Number number) {
                        position.add(number.doubleValue());
                    }
                }
            }
            Double scale = map.get("scale") instanceof Number number ? number.doubleValue() : null;
            return new DeviceDtos.SceneAnchor(model, position.isEmpty() ? null : position, scale);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 写入

    @Transactional
    public DeviceInstance save(DeviceDtos.DeviceSave input) {
        if (input.deviceCode() == null || input.deviceCode().isBlank()) {
            throw new IllegalArgumentException("设备编码不能为空");
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }
        if (input.productId() == null) {
            throw new IllegalArgumentException("必须选择产品");
        }
        Product product = products.get(input.productId());
        if (!"PUBLISHED".equals(product.state)) {
            throw new IllegalArgumentException("产品未发布，不能创建设备: " + product.name);
        }

        String code = input.deviceCode().trim();
        DeviceInstance device = devices.findByDeviceCode(code).orElseGet(DeviceInstance::new);
        if (device.id != null && !Objects.equals(device.productId, product.id)) {
            throw new IllegalArgumentException("设备已绑定产品，不能直接更换产品");
        }
        if (device.id != null && input.deviceCode() != null && !device.deviceCode.equals(code)) {
            throw new IllegalArgumentException("设备编码创建后不可修改");
        }
        String protocolDeviceId =
                input.protocolDeviceId() == null || input.protocolDeviceId().isBlank()
                        ? code
                        : input.protocolDeviceId().trim();
        devices.findByProtocolAndProtocolDeviceId(product.protocol, protocolDeviceId)
                .ifPresent(other -> {
                    if (!Objects.equals(other.id, device.id)) {
                        throw new IllegalArgumentException(
                                "该协议下设备编号已被使用: " + product.protocol + "/" + protocolDeviceId);
                    }
                });
        if (input.stationId() != null && stations.findById(input.stationId()).isEmpty()) {
            throw new IllegalArgumentException("站点不存在: " + input.stationId());
        }
        if (input.parentId() != null) {
            DeviceInstance parent = get(input.parentId());
            if (Objects.equals(parent.id, device.id)) {
                throw new IllegalArgumentException("设备不能作为自己的父设备");
            }
        }

        device.deviceCode = code;
        device.name = input.name().trim();
        device.productId = product.id;
        device.deviceType = product.deviceType;
        device.protocol = product.protocol;
        device.protocolDeviceId = protocolDeviceId;
        device.stationId = input.stationId();
        device.parentId = input.parentId();
        device.enabled = input.enabled() == null || input.enabled();
        device.allowSimulate = input.allowSimulate() == null || input.allowSimulate();
        device.allowWork = input.allowWork() == null || input.allowWork();
        device.remark = input.remark();
        if (input.configuration() != null) {
            device.configuration = ProductService.toJson(input.configuration());
        }
        if (input.sceneAnchor() != null) {
            device.sceneAnchor = anchorJson(input.sceneAnchor());
        }
        device.updatedAt = LocalDateTime.now();
        if (device.id == null) {
            device.createdAt = LocalDateTime.now();
            device.state = DeviceInstance.STATE_NOT_ACTIVE;
        }
        DeviceInstance saved = devices.save(device);

        applyTags(saved, input.tags());
        applyChannels(saved, input.channels(), product);
        return saved;
    }

    private String anchorJson(DeviceDtos.SceneAnchor anchor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", anchor.model());
        if (anchor.position() != null) {
            map.put("position", anchor.position());
        }
        if (anchor.scale() != null) {
            map.put("scale", anchor.scale());
        }
        return ProductService.toJson(map);
    }

    private void applyTags(DeviceInstance device, Map<String, String> input) {
        if (input == null) {
            return;
        }
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey() == null ? null : entry.getKey().trim();
            if (key == null || key.isEmpty()) {
                continue;
            }
            DeviceTag tag = tags.findByDeviceIdAndTagKey(device.id, key)
                    .orElseGet(() -> new DeviceTag(device.id, key, null, "string"));
            tag.tagValue = entry.getValue();
            tag.updatedAt = LocalDateTime.now();
            tags.save(tag);
        }
    }

    /** 通道：显式传入优先；否则按设备类型补齐默认通道（2 枪 / 8 组串 / 4 簇 / 3 相 / 1 镜头）。 */
    private void applyChannels(DeviceInstance device, List<DeviceDtos.ChannelSpec> input, Product product) {
        if (input != null && !input.isEmpty()) {
            for (DeviceDtos.ChannelSpec spec : input) {
                if (spec.channelNo() == null || spec.channelNo().isBlank()) {
                    continue;
                }
                DeviceChannel channel = channels.findByDeviceIdAndChannelNo(
                                device.id, spec.channelNo().trim())
                        .orElseGet(DeviceChannel::new);
                channel.deviceId = device.id;
                channel.channelNo = spec.channelNo().trim();
                channel.channelType =
                        spec.channelType() == null || spec.channelType().isBlank()
                                ? DeviceTypeCatalog.find(device.deviceType)
                                        .map(DeviceTypeCatalog::channelType)
                                        .orElse("CHANNEL")
                                : spec.channelType().trim().toUpperCase(Locale.ROOT);
                channel.name = spec.name();
                channel.enabled = spec.enabled() == null || spec.enabled();
                channel.updatedAt = LocalDateTime.now();
                if (channel.id == null) {
                    channel.createdAt = LocalDateTime.now();
                }
                channels.save(channel);
            }
            return;
        }
        if (channels.countByDeviceId(device.id) > 0) {
            return;
        }
        DeviceTypeCatalog type = DeviceTypeCatalog.find(device.deviceType).orElse(null);
        if (type == null || !type.hasChannel()) {
            return;
        }
        for (String channelNo : defaultChannelNos(type)) {
            DeviceChannel channel = new DeviceChannel();
            channel.deviceId = device.id;
            channel.channelNo = channelNo;
            channel.channelType = type.channelType();
            channel.name = channelNo;
            channel.createdAt = LocalDateTime.now();
            channel.updatedAt = LocalDateTime.now();
            channels.save(channel);
        }
        log.debug(
                "设备 {} 自动补齐 {} 个 {} 通道",
                device.deviceCode,
                defaultChannelNos(type).size(),
                type.channelType());
    }

    private List<String> defaultChannelNos(DeviceTypeCatalog type) {
        return List.of("01", "02");
    }

    @Transactional
    public void delete(Long id) {
        DeviceInstance device = get(id);
        if (!DeviceInstance.STATE_NOT_ACTIVE.equals(device.state)) {
            throw new IllegalArgumentException("只有未激活的设备可以删除，请先注销");
        }
        if (devices.hasDeletionBlockers(id)) {
            throw new IllegalArgumentException("设备存在子设备、业务记录或告警规则，不能删除");
        }
        devices.deleteTags(id);
        devices.deleteChannels(id);
        devices.deleteLatestProperties(id);
        devices.delete(device);
    }

    // ------------------------------------------------------------------ 生命周期

    /** 激活：把设备档案发布到运行时（协议通道下次登录即可命中）。 不在此处建立连接——连接由设备主动发起，平台只负责准入。 */
    @Transactional
    public DeviceInstance deploy(Long id) {
        DeviceInstance device = get(id);
        if (!device.enabled) {
            throw new IllegalArgumentException("设备已停用，不能激活");
        }
        Product product = products.get(device.productId);
        if (!"PUBLISHED".equals(product.state)) {
            throw new IllegalArgumentException("产品未发布，不能激活设备");
        }
        device.state = DeviceInstance.STATE_OFFLINE;
        device.registryTime = LocalDateTime.now();
        device.updatedAt = LocalDateTime.now();
        return devices.save(device);
    }

    /** 注销：断开运行时登记，设备回到未激活，必须重新激活才能登录。 */
    @Transactional
    public DeviceInstance undeploy(Long id) {
        DeviceInstance device = get(id);
        device.state = DeviceInstance.STATE_NOT_ACTIVE;
        device.sessionId = null;
        device.updatedAt = LocalDateTime.now();
        return devices.save(device);
    }

    /** 协议通道登录：按协议编码 + 协议侧编号定位，写入在线状态。 */
    @Transactional
    public DeviceInstance markOnline(DeviceInstance device, String sessionId) {
        device.state = DeviceInstance.STATE_ONLINE;
        device.sessionId = sessionId;
        device.onlineAt = LocalDateTime.now();
        device.lastSeen = LocalDateTime.now();
        device.updatedAt = LocalDateTime.now();
        if (device.registryTime == null) {
            device.registryTime = LocalDateTime.now();
        }
        return devices.save(device);
    }

    @Transactional
    public void touch(String protocol, String protocolDeviceId) {
        devices.findByProtocolAndProtocolDeviceId(normalizeProtocol(protocol), protocolDeviceId)
                .ifPresent(device -> {
                    device.lastSeen = LocalDateTime.now();
                    if (!device.isOnline()) {
                        device.state = DeviceInstance.STATE_ONLINE;
                        device.onlineAt = LocalDateTime.now();
                    }
                    device.updatedAt = LocalDateTime.now();
                    devices.save(device);
                });
    }

    /** 断开连接：只有当前会话才能置离线，避免旧连接覆盖新会话。 */
    @Transactional
    public void markOffline(String protocol, String protocolDeviceId, String sessionId) {
        devices.findByProtocolAndProtocolDeviceId(normalizeProtocol(protocol), protocolDeviceId)
                .ifPresent(device -> {
                    if (sessionId != null && device.sessionId != null && !sessionId.equals(device.sessionId)) {
                        return;
                    }
                    device.state = DeviceInstance.STATE_OFFLINE;
                    device.sessionId = null;
                    device.lastSeen = LocalDateTime.now();
                    device.updatedAt = LocalDateTime.now();
                    devices.save(device);
                });
    }

    @Transactional
    public DeviceInstance setEnabled(Long id, boolean enabled) {
        DeviceInstance device = get(id);
        device.enabled = enabled;
        if (!enabled) {
            device.state = DeviceInstance.STATE_OFFLINE;
            device.sessionId = null;
        }
        device.updatedAt = LocalDateTime.now();
        return devices.save(device);
    }

    @Transactional
    public DeviceInstance move(Long id, Long stationId) {
        DeviceInstance device = get(id);
        if (stationId != null && stations.findById(stationId).isEmpty()) {
            throw new IllegalArgumentException("站点不存在: " + stationId);
        }
        device.stationId = stationId;
        device.updatedAt = LocalDateTime.now();
        return devices.save(device);
    }

    @Transactional
    public DeviceInstance setAllowWork(Long id, boolean allowWork, Integer maxPowerPercent) {
        DeviceInstance device = get(id);
        device.allowWork = allowWork;
        device.updatedAt = LocalDateTime.now();
        DeviceInstance saved = devices.save(device);
        properties.write(id, "", "allowWork", allowWork);
        if (maxPowerPercent != null) {
            properties.write(id, "", "maxPowerPercent", Math.max(0, Math.min(maxPowerPercent, 100)));
        }
        return saved;
    }

    // ------------------------------------------------------------------ 总览

    public DeviceDtos.Overview overview() {
        List<DeviceInstance> all = devices.findByDeviceType("CHARGER");
        long online = all.stream().filter(DeviceInstance::isOnline).count();
        long fault = all.stream()
                .filter(d -> DeviceInstance.STATE_FAULT.equals(d.state))
                .count();
        List<DeviceDtos.DeviceTypeSummary> byType = new ArrayList<>();
        for (DeviceTypeCatalog type : DeviceTypeCatalog.values()) {
            List<DeviceInstance> group = all.stream()
                    .filter(device -> type.name().equals(device.deviceType))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            byType.add(new DeviceDtos.DeviceTypeSummary(
                    type.name(),
                    type.label(),
                    group.size(),
                    group.stream().filter(DeviceInstance::isOnline).count(),
                    group.stream()
                            .filter(d -> DeviceInstance.STATE_FAULT.equals(d.state))
                            .count(),
                    group.stream()
                            .filter(d -> DeviceInstance.STATE_OFFLINE.equals(d.state))
                            .count(),
                    0));
        }
        return new DeviceDtos.Overview(
                all.size(),
                online,
                fault,
                alarms.countOpen(),
                stations.count(),
                products.count(),
                byType,
                alarms.latestOpen(8));
    }

    // ------------------------------------------------------------------ 工具

    public static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "YKC16";
        }
        String value = protocol.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OCPP16", "OCPP1.6J", "OCPP16J" -> "OCPP16J";
            case "YKC", "YKC1.6", "YKC16" -> "YKC16";
            default -> value;
        };
    }

    /** 充电枪数：只数 CONNECTOR 通道，光伏组串/储能簇不算枪。 */
    public int gunCount(Long deviceId) {
        return (int) channels.countByDeviceIdAndChannelType(deviceId, "CONNECTOR");
    }

    /** 协议通道拒绝设备的统一异常，由 API 层转 409。 */
    public static class DeviceRejectedException extends RuntimeException {
        public DeviceRejectedException(String message) {
            super(message);
        }
    }
}
