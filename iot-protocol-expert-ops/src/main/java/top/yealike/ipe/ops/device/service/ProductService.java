package top.yealike.ipe.ops.device.service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.common.PageResult;
import top.yealike.ipe.ops.common.PageSupport;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.Product;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.device.repository.ProductRepository;
import top.yealike.ipe.ops.thingmodel.DeviceTypeCatalog;
import top.yealike.ipe.ops.thingmodel.ThingModel;
import top.yealike.ipe.ops.thingmodel.ThingModelCodec;

/**
 * 产品服务：物模型的宿主。
 *
 * <p>物模型解析结果按产品缓存，并在产品保存时失效。解析一次 JSON 的成本不高， 但设备列表一页 20 条、每条都要拿到属性元数据，重复解析会白白吃掉 CPU。
 */
@Service
public class ProductService {

    private static final Set<String> SORTABLE =
            Set.of("id", "productCode", "name", "deviceType", "protocol", "createdAt", "updatedAt");

    private final ProductRepository products;
    private final DeviceInstanceRepository devices;
    private final Map<Long, CachedModel> modelCache = new ConcurrentHashMap<>();

    public ProductService(ProductRepository products, DeviceInstanceRepository devices) {
        this.products = products;
        this.devices = devices;
    }

    private record CachedModel(String raw, ThingModel model) {}

    // ------------------------------------------------------------------ 查询

    public PageResult<DeviceDtos.ProductRow> query(
            String keyword, String deviceType, String categoryCode, Integer page, Integer size, String sort) {
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("productCode")), like),
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("model")), like)));
            }
            if (deviceType != null && !deviceType.isBlank()) {
                predicates.add(
                        cb.equal(root.get("deviceType"), deviceType.trim().toUpperCase(Locale.ROOT)));
            }
            if (categoryCode != null && !categoryCode.isBlank()) {
                predicates.add(cb.equal(root.get("categoryCode"), categoryCode.trim()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Product> result = products.findAll(spec, PageSupport.pageable(page, size, sort, SORTABLE));
        return PageResult.of(result, this::row);
    }

    public List<Product> findAllById(java.util.Collection<Long> ids) {
        return products.findAllById(ids);
    }

    public long count() {
        return products.count();
    }

    public List<DeviceDtos.ProductRow> listAll() {
        return products.findAll(Sort.by(Sort.Order.asc("id"))).stream()
                .map(this::row)
                .toList();
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

        if (product.id != null && !product.productCode.equals(input.productCode())) {
            throw new IllegalArgumentException("产品编码创建后不可修改");
        }
        product.productCode = input.productCode().trim();
        product.name = input.name().trim();
        product.categoryCode =
                input.categoryCode() == null || input.categoryCode().isBlank()
                        ? type.categoryCode()
                        : input.categoryCode().trim();
        product.deviceType = type.name();
        product.protocol = input.protocol() == null || input.protocol().isBlank()
                ? type.defaultProtocol()
                : input.protocol().trim();
        product.transport = input.transport() == null || input.transport().isBlank()
                ? "NONE"
                : input.transport().trim().toUpperCase(Locale.ROOT);
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
        List<top.yealike.ipe.ops.device.entity.DeviceInstance> affected = devices.findByProductId(product.id);
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
