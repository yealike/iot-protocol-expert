package top.yealike.pep.ops.devices.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.Product;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.ProductMapper;
import top.yealike.pep.ops.devices.mapper.ProtocolDocumentMapper;
import top.yealike.pep.ops.devices.thingmodel.DeviceTypeCatalog;
import top.yealike.pep.ops.devices.thingmodel.ThingModel;
import top.yealike.pep.ops.devices.thingmodel.ThingModelCodec;
import top.yealike.pep.ops.support.page.PageResult;
import top.yealike.pep.ops.support.page.PageSupport;

/**
 * 产品服务：物模型的宿主。
 *
 * <p>物模型解析结果按产品缓存，并在产品保存时失效。解析一次 JSON 的成本不高， 但设备列表一页 20 条、每条都要拿到属性元数据，重复解析会白白吃掉 CPU。
 */
@Service
public class ProductService {

    private static final Set<String> SORTABLE =
            Set.of("id", "productCode", "name", "deviceType", "protocol", "createdAt", "updatedAt");

    private final ProductMapper products;
    private final DeviceInstanceMapper devices;
    private final ProtocolDocumentMapper protocolDocuments;
    private final Map<Long, CachedModel> modelCache = new ConcurrentHashMap<>();

    public ProductService(
            ProductMapper products, DeviceInstanceMapper devices, ProtocolDocumentMapper protocolDocuments) {
        this.products = products;
        this.devices = devices;
        this.protocolDocuments = protocolDocuments;
    }

    private record CachedModel(String raw, ThingModel model) {}

    // ------------------------------------------------------------------ 查询

    public PageResult<DeviceDtos.ProductRow> query(
            String keyword, String deviceType, String categoryCode, Integer page, Integer size, String sort) {
        String effectiveKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String effectiveType = deviceType == null || deviceType.isBlank()
                ? null
                : deviceType.trim().toUpperCase(Locale.ROOT);
        String effectiveCategory = categoryCode == null || categoryCode.isBlank() ? null : categoryCode.trim();
        PageSupport.PageQuery paging = PageSupport.pageable(page, size, sort, SORTABLE);
        List<Product> content = products.search(
                effectiveKeyword, effectiveType, effectiveCategory, paging.orderBy(), paging.size(), paging.offset());
        long total = products.countSearch(effectiveKeyword, effectiveType, effectiveCategory);
        return PageResult.of(content, paging.page(), paging.size(), total, this::row);
    }

    public List<Product> findAllById(java.util.Collection<Long> ids) {
        return products.findAllById(ids);
    }

    /** 按产品批量读取所引用的协议展示信息。 */
    public Map<Long, Map<String, Object>> protocolDocuments(java.util.Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (ProtocolDocumentMapper.Ref ref : protocolDocuments.selectProductRefs(productIds)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ref.id());
            row.put("protocol_code", ref.protocolCode());
            row.put("protocol_name_en", ref.protocolNameEn());
            row.put("document_version", ref.documentVersion());
            result.put(ref.id(), row);
        }
        return result;
    }

    public long count() {
        return products.count();
    }

    public List<DeviceDtos.ProductRow> listAll() {
        return products.findAll().stream().map(this::row).toList();
    }

    public List<Map<String, Object>> deviceTypeCatalog() {
        Map<String, DeviceDtos.ProductRow> byType = new LinkedHashMap<>();
        for (DeviceDtos.ProductRow row : listAll()) {
            byType.putIfAbsent(row.deviceType(), row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> type : DeviceTypeCatalog.describeAll()) {
            Map<String, Object> item = new LinkedHashMap<>(type);
            DeviceDtos.ProductRow template = byType.get(String.valueOf(type.get("deviceType")));
            item.put("templateProductId", template == null ? null : template.id());
            item.put("templateProductCode", template == null ? null : template.productCode());
            item.put("templateMetadata", template == null ? null : metadata(template.id()));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> metadata(Long productId) {
        return ThingModelCodec.toMap(model(productId));
    }

    public Product get(Long id) {
        return products.findById(id).orElseThrow(() -> new IllegalArgumentException("产品不存在: " + id));
    }

    public Optional<Product> findByCode(String code) {
        return products.findByProductCode(code);
    }

    public DeviceDtos.ProductDetail detail(Long id) {
        Product product = get(id);
        return new DeviceDtos.ProductDetail(
                row(product), ThingModelCodec.toMap(model(product.id)), parseObject(product.configuration));
    }

    // ------------------------------------------------------------------ 写入

    @Transactional
    public Product save(DeviceDtos.ProductSave input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("产品名称不能为空");
        }
        DeviceTypeCatalog type = DeviceTypeCatalog.of(input.deviceType());
        Product product =
                products.findByProductCode(requireCode(input.productCode())).orElseGet(Product::new);

        String previousProtocol = product.protocol;
        Long documentId = input.protocolDocumentId();
        String resolvedProtocol = previousProtocol;
        if (documentId != null) {
            ProtocolDocumentMapper.Policy doc = protocolDocuments.selectPolicyForUpdate(documentId);
            if (doc == null) throw new IllegalArgumentException("协议文档不存在");
            if (!"ACTIVE".equals(doc.status())) throw new IllegalArgumentException("协议文档已归档");
            String english = doc.protocolNameEn();
            String version = doc.documentVersion();
            if (english.equalsIgnoreCase("YKC") && version.equalsIgnoreCase("1.6")) resolvedProtocol = "YKC16";
            else if (english.equalsIgnoreCase("OCPP") && version.equalsIgnoreCase("1.6j")) resolvedProtocol = "OCPP16J";
            else throw new IllegalArgumentException("该协议版本尚未接入，当前支持 YKC 1.6 和 OCPP 1.6j");
        } else if (product.id == null || product.protocolDocumentId != null) {
            throw new IllegalArgumentException("请选择协议文档");
        }
        if (product.id != null && !product.productCode.equals(input.productCode())) {
            throw new IllegalArgumentException("产品编码创建后不可修改");
        }
        if (product.id != null && devices.countByProductId(product.id) > 0) {
            String requestedProtocol = resolvedProtocol;
            String requestedTransport =
                    input.transport() == null || input.transport().isBlank()
                            ? "NONE"
                            : input.transport().trim().toUpperCase(Locale.ROOT);
            ThingModel requestedModel = ThingModelCodec.fromMap(input.metadata());
            if (!product.protocol.equals(requestedProtocol)
                    || !product.transport.equals(requestedTransport)
                    || !ThingModelCodec.toMap(ThingModelCodec.parse(product.metadata))
                            .equals(ThingModelCodec.toMap(requestedModel))) {
                throw new IllegalArgumentException("产品已绑定设备，不能直接修改协议或物模型");
            }
        }
        product.productCode = input.productCode().trim();
        product.name = input.name().trim();
        product.categoryCode =
                input.categoryCode() == null || input.categoryCode().isBlank()
                        ? type.categoryCode()
                        : input.categoryCode().trim();
        product.deviceType = type.name();
        product.protocol = resolvedProtocol;
        product.protocolDocumentId = documentId;
        product.transport = input.transport() == null || input.transport().isBlank()
                ? "NONE"
                : input.transport().trim().toUpperCase(Locale.ROOT);
        if (!("YKC16".equals(product.protocol) && "TCP".equals(product.transport))
                && !("OCPP16J".equals(product.protocol) && "WS".equals(product.transport))) {
            throw new IllegalArgumentException("不支持的协议和传输方式组合");
        }
        product.vendor = input.vendor();
        product.model = input.model();
        ThingModel model = ThingModelCodec.fromMap(input.metadata());
        List<String> problems = model.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("物模型不合法: " + String.join("; ", problems));
        }
        product.metadata = ThingModelCodec.toJson(model);
        product.configuration = input.configuration() == null ? null : toJson(input.configuration());
        product.sceneModel = input.sceneModel() == null || input.sceneModel().isBlank()
                ? type.sceneModel()
                : input.sceneModel().trim();
        product.description = input.description();
        product.state = input.state() == null || input.state().isBlank()
                ? "PUBLISHED"
                : input.state().trim().toUpperCase(Locale.ROOT);
        if (product.id == null) {
            product.createdAt = LocalDateTime.now();
        }
        product.updatedAt = LocalDateTime.now();
        Product saved = products.save(product);
        modelCache.remove(saved.id);
        refreshDeviceTypeColumns(saved);
        return saved;
    }

    /** 产品改类型或协议时，同步刷新设备的冗余列，避免列表页出现脏数据。 */
    @Transactional
    public void refreshDeviceTypeColumns(Product product) {
        List<top.yealike.pep.ops.devices.entity.DeviceInstance> affected = devices.findByProductId(product.id);
        boolean changed = false;
        for (var device : affected) {
            if (!product.deviceType.equals(device.deviceType) || !product.protocol.equals(device.protocol)) {
                device.deviceType = product.deviceType;
                device.protocol = product.protocol;
                device.updatedAt = LocalDateTime.now();
                changed = true;
            }
        }
        if (changed) {
            devices.saveAll(affected);
        }
    }

    @Transactional
    public void delete(Long id) {
        Product product = get(id);
        long deviceCount = devices.countByProductId(id);
        if (deviceCount > 0) {
            throw new IllegalArgumentException("产品下仍有 " + deviceCount + " 台设备，不能删除");
        }
        products.delete(product);
        modelCache.remove(id);
    }

    // ------------------------------------------------------------------ 物模型

    /** 产品物模型（带缓存）。 */
    public ThingModel model(Long productId) {
        if (productId == null) {
            return ThingModel.empty();
        }
        CachedModel cached = modelCache.get(productId);
        if (cached != null) {
            return cached.model();
        }
        Product product = get(productId);
        String raw = product.metadata == null ? "{}" : product.metadata;
        ThingModel model = ThingModelCodec.parse(raw);
        modelCache.put(productId, new CachedModel(raw, model));
        return model;
    }

    public void evict(Long productId) {
        modelCache.remove(productId);
    }

    // ------------------------------------------------------------------ 视图

    public DeviceDtos.ProductRow row(Product product) {
        ThingModel model = model(product.id);
        ProtocolDocumentMapper.Ref doc =
                product.protocolDocumentId == null ? null : protocolDocuments.selectRef(product.protocolDocumentId);
        return new DeviceDtos.ProductRow(
                product.id,
                product.productCode,
                product.name,
                product.categoryCode,
                product.deviceType,
                DeviceTypeCatalog.find(product.deviceType)
                        .map(DeviceTypeCatalog::label)
                        .orElse(product.deviceType),
                product.protocol,
                product.protocolDocumentId,
                doc == null ? null : doc.protocolCode(),
                doc == null ? null : doc.protocolNameEn(),
                doc == null ? null : doc.documentVersion(),
                product.transport,
                product.vendor,
                product.model,
                product.sceneModel,
                product.description,
                product.state,
                devices.countByProductId(product.id),
                model.properties().size(),
                model.functions().size(),
                model.events().size());
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("产品编码不能为空");
        }
        return code.trim();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ThingModelCodec.mapper().readValue(json, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    static String toJson(Map<String, Object> map) {
        try {
            return ThingModelCodec.mapper().writeValueAsString(map);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置无法序列化为 JSON", e);
        }
    }
}
