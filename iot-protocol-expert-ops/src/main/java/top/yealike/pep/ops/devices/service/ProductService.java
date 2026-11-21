package top.yealike.pep.ops.devices.service;

import java.time.LocalDateTime;
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
import top.yealike.pep.ops.devices.thingmodel.ThingModel;
import top.yealike.pep.ops.devices.thingmodel.ThingModelCodec;
import top.yealike.pep.ops.support.page.PageResult;
import top.yealike.pep.ops.support.page.PageSupport;

/** 充电桩产品服务。产品协议创建后不可切换，物模型与标准枪数由产品统一维护。 */
@Service
public class ProductService {

    private static final Set<String> SORTABLE =
            Set.of("id", "productCode", "name", "protocol", "connectorCount", "createdAt", "updatedAt");

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

    public PageResult<DeviceDtos.ProductRow> query(
            String keyword, String categoryCode, Integer page, Integer size, String sort) {
        String effectiveKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String effectiveCategory = categoryCode == null || categoryCode.isBlank() ? null : categoryCode.trim();
        PageSupport.PageQuery paging = PageSupport.pageable(page, size, sort, SORTABLE);
        List<Product> content =
                products.search(effectiveKeyword, effectiveCategory, paging.orderBy(), paging.size(), paging.offset());
        long total = products.countSearch(effectiveKeyword, effectiveCategory);
        return PageResult.of(content, paging.page(), paging.size(), total, this::row);
    }

    public List<Product> findAllById(java.util.Collection<Long> ids) {
        return products.findAllById(ids);
    }

    public Map<Long, ProtocolDocumentMapper.Ref> protocolDocuments(java.util.Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProtocolDocumentMapper.Ref> result = new LinkedHashMap<>();
        for (ProtocolDocumentMapper.Ref ref : protocolDocuments.selectProductRefs(productIds)) {
            result.put(ref.id(), ref);
        }
        return result;
    }

    public long count() {
        return products.count();
    }

    public List<DeviceDtos.ProductRow> listAll() {
        return products.findAll().stream().map(this::row).toList();
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

    @Transactional
    public Product save(DeviceDtos.ProductSave input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("产品名称不能为空");
        }
        Product product =
                products.findByProductCode(requireCode(input.productCode())).orElseGet(Product::new);

        if (product.id != null
                && !product.productCode.equals(input.productCode().trim())) {
            throw new IllegalArgumentException("产品编码创建后不可修改");
        }

        Long documentId = input.protocolDocumentId() == null ? product.protocolDocumentId : input.protocolDocumentId();
        if (documentId == null) {
            throw new IllegalArgumentException("请选择协议文档");
        }
        ProtocolDocumentMapper.Policy doc = protocolDocuments.selectPolicyForUpdate(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("协议文档不存在");
        }
        if (!"ACTIVE".equals(doc.status())) {
            throw new IllegalArgumentException("协议文档已归档");
        }
        String resolvedProtocol = protocolOf(doc);
        if (product.id != null && !product.protocol.equals(resolvedProtocol)) {
            throw new IllegalArgumentException("产品协议创建后不可切换");
        }

        String transport = input.transport() == null || input.transport().isBlank()
                ? ("OCPP16J".equals(resolvedProtocol) ? "WS" : "TCP")
                : input.transport().trim().toUpperCase(Locale.ROOT);
        if (!("YKC16".equals(resolvedProtocol) && "TCP".equals(transport))
                && !("OCPP16J".equals(resolvedProtocol) && "WS".equals(transport))) {
            throw new IllegalArgumentException("不支持的协议和传输方式组合");
        }

        long deviceCount = product.id == null ? 0 : devices.countByProductId(product.id);
        int connectorCount = input.connectorCount() == null
                ? (product.id == null ? 1 : product.connectorCount)
                : input.connectorCount();
        if (connectorCount < 1 || connectorCount > 99) {
            throw new IllegalArgumentException("标准枪数必须为 1 至 99");
        }
        if (deviceCount > 0 && connectorCount != product.connectorCount) {
            throw new IllegalArgumentException("产品已绑定设备，不能修改标准枪数");
        }

        ThingModel model = input.metadata() == null ? ThingModel.empty() : ThingModelCodec.fromMap(input.metadata());
        if (deviceCount > 0) {
            ThingModel existingModel = model(product.id);
            if (!ThingModelCodec.toMap(existingModel).equals(ThingModelCodec.toMap(model))) {
                throw new IllegalArgumentException("产品已绑定设备，不能直接修改物模型");
            }
        } else {
            List<String> problems = model.validate();
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("物模型不合法: " + String.join("; ", problems));
            }
        }

        product.productCode = input.productCode().trim();
        product.name = input.name().trim();
        product.categoryCode =
                input.categoryCode() == null || input.categoryCode().isBlank()
                        ? "charging"
                        : input.categoryCode().trim();
        product.protocol = resolvedProtocol;
        product.protocolDocumentId = documentId;
        product.transport = transport;
        product.vendor = trimToNull(input.vendor());
        product.model = trimToNull(input.model());
        product.metadata = ThingModelCodec.toJson(model);
        product.configuration = input.configuration() == null ? null : toJson(input.configuration());
        product.connectorCount = connectorCount;
        product.description = trimToNull(input.description());
        product.state = input.state() == null || input.state().isBlank()
                ? "PUBLISHED"
                : input.state().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DRAFT", "PUBLISHED").contains(product.state)) {
            throw new IllegalArgumentException("产品状态必须为 DRAFT 或 PUBLISHED");
        }
        if (product.id == null) {
            product.createdAt = LocalDateTime.now();
        }
        product.updatedAt = LocalDateTime.now();

        Product saved = products.save(product);
        modelCache.remove(saved.id);
        return saved;
    }

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

    public DeviceDtos.ProductRow row(Product product) {
        ThingModel model = model(product.id);
        ProtocolDocumentMapper.Ref doc =
                product.protocolDocumentId == null ? null : protocolDocuments.selectRef(product.protocolDocumentId);
        return new DeviceDtos.ProductRow(
                product.id,
                product.productCode,
                product.name,
                product.categoryCode,
                product.protocol,
                product.protocolDocumentId,
                doc == null ? fallbackProtocolName(product.protocol) : doc.protocolNameEn(),
                doc == null ? fallbackProtocolVersion(product.protocol) : doc.documentVersion(),
                product.transport,
                product.vendor,
                product.model,
                product.connectorCount,
                product.description,
                product.state,
                devices.countByProductId(product.id),
                model.properties().size(),
                model.functions().size(),
                model.events().size());
    }

    private static String protocolOf(ProtocolDocumentMapper.Policy doc) {
        String english =
                doc.protocolNameEn() == null ? "" : doc.protocolNameEn().trim();
        String version =
                doc.documentVersion() == null ? "" : doc.documentVersion().trim();
        if (english.equalsIgnoreCase("YKC") && version.equalsIgnoreCase("1.6")) {
            return "YKC16";
        }
        if (english.equalsIgnoreCase("OCPP") && version.equalsIgnoreCase("1.6j")) {
            return "OCPP16J";
        }
        throw new IllegalArgumentException("该协议版本尚未接入，当前支持 YKC 1.6 和 OCPP 1.6J");
    }

    private static String fallbackProtocolName(String protocol) {
        return "OCPP16J".equals(protocol) ? "OCPP" : "YKC";
    }

    private static String fallbackProtocolVersion(String protocol) {
        return "OCPP16J".equals(protocol) ? "1.6J" : "1.6";
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("产品编码不能为空");
        }
        return code.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
