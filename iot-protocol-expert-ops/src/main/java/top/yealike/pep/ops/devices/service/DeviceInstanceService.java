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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.charging.domain.CommandRecord;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.devices.domain.DeviceChannel;
import top.yealike.pep.ops.devices.domain.DeviceEventLog;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DevicePropertyLatest;
import top.yealike.pep.ops.devices.domain.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.domain.DeviceRejectedException;
import top.yealike.pep.ops.devices.domain.Product;
import top.yealike.pep.ops.devices.domain.ProtocolDocumentRef;
import top.yealike.pep.ops.devices.dto.CommandView;
import top.yealike.pep.ops.devices.dto.DeviceDetail;
import top.yealike.pep.ops.devices.dto.DeviceQuery;
import top.yealike.pep.ops.devices.dto.DeviceRow;
import top.yealike.pep.ops.devices.dto.DeviceSave;
import top.yealike.pep.ops.devices.dto.EventView;
import top.yealike.pep.ops.devices.dto.Overview;
import top.yealike.pep.ops.devices.dto.ProtocolMappingInput;
import top.yealike.pep.ops.devices.dto.ProtocolMappingView;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.thingmodel.ThingModelCodec;
import top.yealike.pep.ops.devices.thingmodel.ThingValues;
import top.yealike.pep.ops.devices.thingmodel.domain.EventMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.PropertyMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.StoredValue;
import top.yealike.pep.ops.devices.thingmodel.domain.ThingModel;
import top.yealike.pep.ops.support.page.PageQuery;
import top.yealike.pep.ops.support.page.PageResult;
import top.yealike.pep.ops.support.page.PageSupport;

/** 充电桩档案、枪口、第三方映射和实时视图。 */
@Service
public class DeviceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceInstanceService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SORTABLE =
            Set.of("id", "deviceCode", "name", "protocol", "state", "lastSeen", "createdAt", "updatedAt");
    private static final List<String> CHANNEL_METRIC_PRIORITY =
            List.of("gunState", "soc", "chargeEnergy", "voltage", "current", "chargeAmount");

    private final DeviceInstanceMapper devices;
    private final DeviceChannelMapper channels;
    private final DeviceEventLogMapper events;
    private final DevicePropertyLatestMapper latest;
    private final DeviceProtocolMappingMapper mappings;
    private final ProductService products;
    private final ThingModelResolver models;
    private final DevicePropertyService properties;
    private final CommandRecordMapper commands;

    public DeviceInstanceService(
            DeviceInstanceMapper devices,
            DeviceChannelMapper channels,
            DeviceEventLogMapper events,
            DevicePropertyLatestMapper latest,
            DeviceProtocolMappingMapper mappings,
            ProductService products,
            ThingModelResolver models,
            DevicePropertyService properties,
            CommandRecordMapper commands) {
        this.devices = devices;
        this.channels = channels;
        this.events = events;
        this.latest = latest;
        this.mappings = mappings;
        this.products = products;
        this.models = models;
        this.properties = properties;
        this.commands = commands;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<DeviceRow> query(DeviceQuery query) {
        SearchSpec search = SearchSpec.of(query.keyword());
        List<String> states = query.states() == null
                ? null
                : query.states().stream().map(DeviceInstance::normalizeState).toList();
        PageQuery paging = PageSupport.pageable(query.page(), query.size(), query.sort(), SORTABLE);
        List<DeviceInstance> content = devices.search(
                search.keyword(),
                search.deviceSn(),
                search.platformGunNo(),
                states,
                query.productId(),
                query.enabled(),
                paging.orderBy(),
                paging.size(),
                paging.offset());
        long total = devices.countSearch(
                search.keyword(),
                search.deviceSn(),
                search.platformGunNo(),
                states,
                query.productId(),
                query.enabled());
        ViewContext context = context(content);
        return PageResult.of(
                content, paging.page(), paging.size(), total, device -> row(device, context, search.gunNo()));
    }

    public DeviceInstance get(Long id) {
        return devices.findById(id).orElseThrow(() -> new IllegalArgumentException("设备不存在: " + id));
    }

    public Optional<DeviceInstance> findByCode(String deviceCode) {
        return devices.findByDeviceCode(deviceCode);
    }

    /**
     * 协议入口定位设备。8 位平台 SN 直接命中；其他输入按协议第三方设备号映射命中。
     * 未登记映射一律拒绝，不自动注册。
     */
    public DeviceInstance resolve(String protocol, String identifier) {
        String normalizedProtocol = normalizeProtocol(protocol);
        if (identifier == null || identifier.isBlank()) {
            throw new DeviceRejectedException("设备编号为空");
        }
        String value = identifier.trim();
        DeviceInstance device;
        if (value.matches("[1-9][0-9]{7}")) {
            device = devices.findByDeviceCode(value).orElseThrow(() -> new DeviceRejectedException("设备未登记: " + value));
        } else {
            DeviceProtocolMapping mapping = mappings.findByProtocolAndThirdPartyDeviceId(normalizedProtocol, value)
                    .filter(item -> item.enabled)
                    .orElseThrow(() -> new DeviceRejectedException("第三方设备未登记: " + normalizedProtocol + "/" + value));
            device = devices.findById(mapping.deviceId)
                    .orElseThrow(() -> new DeviceRejectedException("设备映射指向不存在的设备: " + mapping.deviceId));
        }
        if (!normalizedProtocol.equals(device.protocol)) {
            throw new DeviceRejectedException("设备协议不匹配: " + normalizedProtocol + "/" + value);
        }
        return device;
    }

    public List<DeviceInstance> simulatorDevices() {
        return devices.findAll().stream()
                .filter(device -> device.enabled && device.allowSimulate)
                .sorted(Comparator.comparing(device -> device.id))
                .toList();
    }

    public DeviceDetail detail(Long id) {
        DeviceInstance device = get(id);
        ThingModel model = models.of(device);
        ViewContext context = context(List.of(device));
        return new DeviceDetail(
                row(device, context, null),
                properties.channelViews(device, model),
                properties.propertyViews(device, model),
                eventViews(device.id),
                commandViews(device.id),
                mappingViews(device, context.mappings().get(device.id)));
    }

    private List<EventView> eventViews(Long deviceId) {
        ThingModel model = models.of(deviceId);
        List<EventView> views = new ArrayList<>();
        for (DeviceEventLog event : events.findTop50ByDeviceIdOrderByOccurredAtDesc(deviceId)) {
            EventMetadata metadata = model.event(event.eventId).orElse(null);
            views.add(new EventView(
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

    private List<CommandView> commandViews(Long deviceId) {
        List<CommandView> views = new ArrayList<>();
        for (CommandRecord record : commands.findTop100ByDeviceIdOrderByIdDesc(deviceId)) {
            views.add(new CommandView(
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

    // ------------------------------------------------------------------ 视图

    private record ViewContext(
            Map<Long, Product> productById,
            Map<Long, ProtocolDocumentRef> protocolDocuments,
            Map<Long, Map<String, DevicePropertyLatest>> properties,
            Map<Long, List<DeviceChannel>> channels,
            Map<Long, DeviceProtocolMapping> mappings) {}

    private ViewContext context(List<DeviceInstance> list) {
        Map<Long, Product> productById = new LinkedHashMap<>();
        Map<Long, Map<String, DevicePropertyLatest>> propertyMap = new LinkedHashMap<>();
        Map<Long, List<DeviceChannel>> channelMap = new LinkedHashMap<>();
        Map<Long, DeviceProtocolMapping> mappingMap = new LinkedHashMap<>();
        if (list.isEmpty()) {
            return new ViewContext(productById, Map.of(), propertyMap, channelMap, mappingMap);
        }
        Set<Long> productIds = new LinkedHashSet<>();
        for (DeviceInstance device : list) {
            if (device.productId != null) {
                productIds.add(device.productId);
            }
        }
        for (Product product : products.findAllById(productIds)) {
            productById.put(product.id, product);
        }
        List<Long> deviceIds = list.stream().map(device -> device.id).toList();
        for (DevicePropertyLatest row : latest.findByDeviceIdIn(deviceIds)) {
            propertyMap
                    .computeIfAbsent(row.deviceId, key -> new LinkedHashMap<>())
                    .put(row.channelNo + "|" + row.propertyId, row);
        }
        for (DeviceChannel channel : channels.findByDeviceIdIn(deviceIds)) {
            channelMap
                    .computeIfAbsent(channel.deviceId, key -> new ArrayList<>())
                    .add(channel);
        }
        for (DeviceProtocolMapping mapping : mappings.selectByDeviceIds(deviceIds)) {
            mappingMap.put(mapping.deviceId, mapping);
        }
        return new ViewContext(
                productById, products.protocolDocuments(productIds), propertyMap, channelMap, mappingMap);
    }

    public DeviceRow view(DeviceInstance device) {
        return row(device, context(List.of(device)), null);
    }

    public List<DeviceRow> rows(List<DeviceInstance> devices) {
        if (devices.isEmpty()) {
            return List.of();
        }
        ViewContext context = context(devices);
        return devices.stream().map(device -> row(device, context, null)).toList();
    }

    private DeviceRow row(DeviceInstance device, ViewContext context, String matchedGunNo) {
        Product product = context.productById().get(device.productId);
        ProtocolDocumentRef document = context.protocolDocuments().get(device.productId);
        ThingModel model = models.of(device);
        Map<String, DevicePropertyLatest> stored = context.properties().get(device.id);
        if (stored == null) {
            stored = properties.latestMap(device.id);
        }
        List<DeviceChannel> deviceChannels = context.channels().getOrDefault(device.id, List.of());

        return new DeviceRow(
                device.id,
                device.deviceCode,
                device.name,
                device.productId,
                product == null ? null : product.name,
                device.protocol,
                document == null ? fallbackProtocolName(device.protocol) : document.protocolNameEn(),
                document == null ? fallbackProtocolVersion(device.protocol) : document.documentVersion(),
                device.getStateOrOffline(),
                device.enabled,
                keyMetrics(model, stored, deviceChannels),
                format(device.lastSeen),
                deviceChannels.size(),
                matchedGunNo);
    }

    private Map<String, Object> keyMetrics(
            ThingModel model, Map<String, DevicePropertyLatest> stored, List<DeviceChannel> deviceChannels) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }

        Map<String, PropertyMetadata> propertiesById = model.propertyIndex();
        if (!deviceChannels.isEmpty()) {
            DeviceChannel focus = focusChannel(stored, deviceChannels);
            for (DeviceChannel channel : deviceChannels) {
                addMetric(result, propertiesById.get("gunState"), stored, channel);
            }
            for (String propertyId : CHANNEL_METRIC_PRIORITY) {
                if (result.size() >= 4) {
                    break;
                }
                if (!"gunState".equals(propertyId)) {
                    addMetric(result, propertiesById.get(propertyId), stored, focus);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        for (PropertyMetadata property : model.deviceProperties()) {
            DevicePropertyLatest row = stored.get("|" + property.id());
            if (row == null) {
                continue;
            }
            putMetric(result, property, row, null);
            if (result.size() >= 4) {
                break;
            }
        }
        return result;
    }

    private DeviceChannel focusChannel(Map<String, DevicePropertyLatest> stored, List<DeviceChannel> deviceChannels) {
        for (DeviceChannel channel : deviceChannels) {
            DevicePropertyLatest state = stored.get(channel.channelNo + "|gunState");
            if (state != null && "charging".equals(state.valueText)) {
                return channel;
            }
        }

        DeviceChannel latest = deviceChannels.get(0);
        LocalDateTime latestTime = null;
        for (DeviceChannel channel : deviceChannels) {
            String prefix = channel.channelNo + "|";
            for (Map.Entry<String, DevicePropertyLatest> entry : stored.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                LocalDateTime valueTime = entry.getValue().valueTime;
                if (valueTime != null && (latestTime == null || valueTime.isAfter(latestTime))) {
                    latest = channel;
                    latestTime = valueTime;
                }
            }
        }
        return latest;
    }

    private void addMetric(
            Map<String, Object> result,
            PropertyMetadata property,
            Map<String, DevicePropertyLatest> stored,
            DeviceChannel channel) {
        if (property == null || result.size() >= 4) {
            return;
        }
        DevicePropertyLatest row = stored.get(channel.channelNo + "|" + property.id());
        if (row != null) {
            putMetric(result, property, row, channel.channelNo);
        }
    }

    private void putMetric(
            Map<String, Object> result, PropertyMetadata property, DevicePropertyLatest row, String channelNo) {
        StoredValue value = new StoredValue(row.valueType, row.valueNum, row.valueText, row.valueJson, row.valueTime);
        String display = ThingValues.format(property.valueType(), value);
        if (display == null) {
            return;
        }
        String unit = property.valueType() == null || property.valueType().unit() == null
                ? ""
                : " " + property.valueType().unit();
        String prefix = channelNo == null ? "" : channelNo + "枪 · ";
        result.put(channelNo == null ? property.id() : channelNo + "|" + property.id(), prefix + display + unit);
    }

    private List<ProtocolMappingView> mappingViews(DeviceInstance device, DeviceProtocolMapping mapping) {
        List<ProtocolMappingView> result = new ArrayList<>();
        for (DeviceChannel channel : channels.findByDeviceIdOrderByChannelNoAsc(device.id)) {
            result.add(new ProtocolMappingView(
                    channel.channelNo,
                    channel.platformGunNo,
                    mapping == null ? null : mapping.thirdPartyDeviceId,
                    channel.protocolConnectorNo,
                    mapping != null && mapping.enabled));
        }
        return result;
    }

    // ------------------------------------------------------------------ 写入

    @Transactional
    public DeviceInstance create(DeviceSave input) {
        validateSave(input, true);
        Product product = requirePublishedProduct(input.productId());
        String sn = input.sn() == null || input.sn().isBlank() ? allocateDeviceSn() : validateDeviceSn(input.sn());
        if (devices.findByDeviceCode(sn).isPresent()) {
            throw new IllegalArgumentException("设备 SN 已存在: " + sn);
        }

        DeviceInstance device = new DeviceInstance();
        device.deviceCode = sn;
        device.name = input.name().trim();
        device.productId = product.id;
        device.protocol = product.protocol;
        device.state = DeviceInstance.STATE_NOT_ACTIVE;
        device.enabled = input.enabled() == null || input.enabled();
        device.allowSimulate = true;
        device.allowWork = true;
        device.configuration = "{}";
        device.createdAt = LocalDateTime.now();
        device.updatedAt = device.createdAt;
        DeviceInstance saved;
        try {
            saved = devices.save(device);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("设备 SN 已存在: " + sn, e);
        }
        ensureProductChannels(saved, product);
        replaceMappings(saved, product, input.protocolMappings());
        return saved;
    }

    @Transactional
    public DeviceInstance update(Long id, DeviceSave input) {
        validateSave(input, false);
        DeviceInstance device = get(id);
        if (input.sn() != null
                && !input.sn().isBlank()
                && !device.deviceCode.equals(input.sn().trim())) {
            throw new IllegalArgumentException("设备 SN 创建后不可修改");
        }
        if (input.productId() != null && !Objects.equals(device.productId, input.productId())) {
            throw new IllegalArgumentException("设备已绑定产品，不能切换产品");
        }
        Product product = products.get(device.productId);
        if (!device.protocol.equals(product.protocol)) {
            throw new IllegalStateException("设备协议与产品配置不一致，请检查数据");
        }
        device.name = input.name().trim();
        if (input.enabled() != null) {
            device.enabled = input.enabled();
        }
        device.updatedAt = LocalDateTime.now();
        DeviceInstance saved = devices.save(device);
        ensureProductChannels(saved, product);
        replaceMappings(saved, product, input.protocolMappings());
        return saved;
    }

    private static void validateSave(DeviceSave input, boolean creating) {
        if (input == null) {
            throw new IllegalArgumentException("设备请求不能为空");
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }
        if (creating && input.productId() == null) {
            throw new IllegalArgumentException("必须选择充电桩产品");
        }
    }

    private Product requirePublishedProduct(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("必须选择充电桩产品");
        }
        Product product = products.get(productId);
        if (!"PUBLISHED".equals(product.state)) {
            throw new IllegalArgumentException("产品未发布，不能创建充电桩: " + product.name);
        }
        return product;
    }

    private String allocateDeviceSn() {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String candidate = devices.selectNextDeviceSnCandidate();
            if (candidate == null) {
                break;
            }
            if (devices.findByDeviceCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("平台设备 SN 已耗尽");
    }

    private static String validateDeviceSn(String raw) {
        String value = raw.trim();
        if (!value.matches("[1-9][0-9]{7}")) {
            throw new IllegalArgumentException("设备 SN 必须为 8 位纯数字且不小于 10000000");
        }
        return value;
    }

    private void ensureProductChannels(DeviceInstance device, Product product) {
        for (int i = 1; i <= product.connectorCount; i++) {
            String gunNo = String.format("%02d", i);
            DeviceChannel channel =
                    channels.findByDeviceIdAndChannelNo(device.id, gunNo).orElseGet(DeviceChannel::new);
            channel.deviceId = device.id;
            channel.channelNo = gunNo;
            channel.platformGunNo = device.deviceCode + gunNo;
            channel.name = channel.name == null ? "充电枪 " + gunNo : channel.name;
            channel.enabled = channel.id == null || channel.enabled;
            channel.updatedAt = LocalDateTime.now();
            if (channel.id == null) {
                channel.createdAt = channel.updatedAt;
            }
            channels.save(channel);
        }
    }

    @Transactional
    public DeviceInstance replaceMappings(DeviceInstance device, Product product, List<ProtocolMappingInput> inputs) {
        if (inputs == null) {
            return device;
        }
        List<ProtocolMappingInput> values =
                inputs.stream().filter(Objects::nonNull).toList();
        Map<String, ProtocolMappingInput> byGun = new LinkedHashMap<>();
        for (ProtocolMappingInput value : values) {
            String gunNo = normalizeGunNo(value.gunNo(), product.connectorCount);
            if (byGun.putIfAbsent(gunNo, value) != null) {
                throw new IllegalArgumentException("枪号重复: " + gunNo);
            }
        }

        Set<String> externalIds = new LinkedHashSet<>();
        Set<String> connectorIds = new LinkedHashSet<>();
        Set<Boolean> enabledValues = new LinkedHashSet<>();
        for (ProtocolMappingInput value : values) {
            String thirdParty = trimToNull(value.thirdPartyDeviceId());
            String connector = trimToNull(value.protocolConnectorNo());
            if (thirdParty == null) {
                if (connector != null) {
                    throw new IllegalArgumentException("配置第三方枪号前必须填写第三方设备号");
                }
                continue;
            }
            validateThirdPartyDeviceId(product.protocol, thirdParty);
            externalIds.add(thirdParty);
            String gunNo = normalizeGunNo(value.gunNo(), product.connectorCount);
            connectorIds.add(normalizeProtocolConnectorNo(connector == null ? gunNo : connector));
            if (value.enabled() != null) {
                enabledValues.add(value.enabled());
            }
        }
        if (externalIds.size() > 1) {
            throw new IllegalArgumentException("同一台充电桩只能映射一个第三方设备号");
        }
        if (enabledValues.size() > 1) {
            throw new IllegalArgumentException("同一台充电桩的映射启用状态必须一致");
        }
        if (connectorIds.size()
                != values.stream()
                        .filter(value -> trimToNull(value.thirdPartyDeviceId()) != null)
                        .count()) {
            throw new IllegalArgumentException("第三方枪号不能重复");
        }

        DeviceProtocolMapping mapping = mappings.findByDeviceId(device.id).orElse(null);
        if (externalIds.isEmpty()) {
            if (mapping != null) {
                mappings.deleteByDeviceId(device.id);
            }
            clearProtocolConnectors(device.id);
            return device;
        }
        long configuredGuns = values.stream()
                .filter(value -> trimToNull(value.thirdPartyDeviceId()) != null)
                .count();
        if (configuredGuns != product.connectorCount) {
            throw new IllegalArgumentException("配置第三方设备号时必须完整映射全部 " + product.connectorCount + " 把充电枪");
        }

        String externalId = externalIds.iterator().next();
        mappings.findByProtocolAndThirdPartyDeviceId(product.protocol, externalId)
                .filter(other -> !Objects.equals(other.deviceId, device.id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("第三方设备号已被其他充电桩使用: " + product.protocol + "/" + externalId);
                });
        if (mapping == null) {
            mapping = new DeviceProtocolMapping();
            mapping.deviceId = device.id;
            mapping.createdAt = LocalDateTime.now();
        }
        mapping.protocol = product.protocol;
        mapping.thirdPartyDeviceId = externalId;
        mapping.enabled = !enabledValues.contains(false);
        mapping.updatedAt = LocalDateTime.now();
        try {
            mappings.save(mapping);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("第三方设备号已被其他充电桩使用: " + product.protocol + "/" + externalId, e);
        }

        for (DeviceChannel channel : channels.findByDeviceIdOrderByChannelNoAsc(device.id)) {
            ProtocolMappingInput value = byGun.get(channel.channelNo);
            String connector = null;
            if (value != null && trimToNull(value.thirdPartyDeviceId()) != null) {
                connector = normalizeProtocolConnectorNo(
                        trimToNull(value.protocolConnectorNo()) == null
                                ? channel.channelNo
                                : value.protocolConnectorNo());
            }
            if (!Objects.equals(channel.protocolConnectorNo, connector)) {
                channel.protocolConnectorNo = connector;
                channel.updatedAt = LocalDateTime.now();
                channels.save(channel);
            }
        }
        return device;
    }

    @Transactional
    public DeviceInstance saveMappings(Long id, List<ProtocolMappingInput> inputs) {
        DeviceInstance device = get(id);
        return replaceMappings(device, products.get(device.productId), inputs);
    }

    private void clearProtocolConnectors(Long deviceId) {
        for (DeviceChannel channel : channels.findByDeviceIdOrderByChannelNoAsc(deviceId)) {
            if (channel.protocolConnectorNo != null) {
                channel.protocolConnectorNo = null;
                channel.updatedAt = LocalDateTime.now();
                channels.save(channel);
            }
        }
    }

    @Transactional
    public void delete(Long id) {
        DeviceInstance device = get(id);
        if (!DeviceInstance.STATE_NOT_ACTIVE.equals(device.state)) {
            throw new IllegalArgumentException("只有未激活的充电桩可以删除，请先注销");
        }
        if (devices.hasDeletionBlockers(id)) {
            throw new IllegalArgumentException("充电桩存在业务记录，不能删除");
        }
        mappings.deleteByDeviceId(id);
        channels.deleteByDeviceId(id);
        devices.deleteTags(id);
        devices.deleteLatestProperties(id);
        devices.delete(device);
    }

    // ------------------------------------------------------------------ 生命周期

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

    @Transactional
    public DeviceInstance undeploy(Long id) {
        DeviceInstance device = get(id);
        device.state = DeviceInstance.STATE_NOT_ACTIVE;
        device.sessionId = null;
        device.updatedAt = LocalDateTime.now();
        return devices.save(device);
    }

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
    public void touch(String protocol, String identifier) {
        resolveIfPresent(protocol, identifier).ifPresent(device -> {
            device.lastSeen = LocalDateTime.now();
            if (!device.isOnline()) {
                device.state = DeviceInstance.STATE_ONLINE;
                device.onlineAt = LocalDateTime.now();
            }
            device.updatedAt = LocalDateTime.now();
            devices.save(device);
        });
    }

    @Transactional
    public void markOffline(String protocol, String identifier, String sessionId) {
        resolveIfPresent(protocol, identifier).ifPresent(device -> {
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

    // ------------------------------------------------------------------ 总览及工具

    public Overview overview() {
        List<DeviceInstance> all = devices.findAll();
        long online = all.stream().filter(DeviceInstance::isOnline).count();
        long fault = all.stream()
                .filter(device -> DeviceInstance.STATE_FAULT.equals(device.state))
                .count();
        return new Overview(all.size(), online, fault, products.count());
    }

    public int gunCount(Long deviceId) {
        return (int) channels.countByDeviceId(deviceId);
    }

    public static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "YKC16";
        }
        String value = protocol.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OCPP16", "OCPP1.6J", "OCPP16J" -> "OCPP16J";
            case "YKC", "YKC1.6", "YKC16" -> "YKC16";
            default -> throw new IllegalArgumentException("不支持的充电协议: " + protocol);
        };
    }

    private Optional<DeviceInstance> resolveIfPresent(String protocol, String identifier) {
        try {
            return Optional.of(resolve(protocol, identifier));
        } catch (DeviceRejectedException e) {
            log.debug("忽略未登记设备状态更新: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String fallbackProtocolName(String protocol) {
        return "OCPP16J".equals(protocol) ? "OCPP" : "YKC";
    }

    private static String fallbackProtocolVersion(String protocol) {
        return "OCPP16J".equals(protocol) ? "1.6J" : "1.6";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeGunNo(String raw, int connectorCount) {
        String value = trimToNull(raw);
        if (value == null || !value.matches("[0-9]{1,2}")) {
            throw new IllegalArgumentException("枪号必须为 1 至 " + connectorCount + ": " + raw);
        }
        int number = Integer.parseInt(value);
        if (number < 1 || number > connectorCount) {
            throw new IllegalArgumentException("枪号不属于产品: " + value);
        }
        return String.format("%02d", number);
    }

    private static String normalizeProtocolConnectorNo(String raw) {
        String value = trimToNull(raw);
        if (value == null || !value.matches("[0-9]{1,2}")) {
            throw new IllegalArgumentException("第三方枪号必须为 1 至 99: " + raw);
        }
        int number = Integer.parseInt(value);
        if (number < 1 || number > 99) {
            throw new IllegalArgumentException("第三方枪号必须为 1 至 99: " + raw);
        }
        return String.format("%02d", number);
    }

    private static void validateThirdPartyDeviceId(String protocol, String value) {
        if (value.length() > 64) {
            throw new IllegalArgumentException("第三方设备号不能超过 64 个字符");
        }
        if (value.matches("[1-9][0-9]{7}")) {
            throw new IllegalArgumentException("第三方设备号不能使用 8 位平台 SN 格式");
        }
        if ("YKC16".equals(protocol) && !value.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("云快充第三方设备号必须为 14 位数字");
        }
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

    private record SearchSpec(String keyword, String deviceSn, String platformGunNo, String gunNo) {
        static SearchSpec of(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.matches("[1-9][0-9]{7}")) {
                return new SearchSpec(null, value, null, null);
            }
            if (value.matches("[1-9][0-9]{9}")) {
                return new SearchSpec(null, value.substring(0, 8), value, value.substring(8));
            }
            return new SearchSpec(value.isBlank() ? null : value, null, null, null);
        }
    }
}
