package top.yealike.ipe.ops.device.api;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.common.PageResult;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.Product;
import top.yealike.ipe.ops.device.service.ProductService;
import top.yealike.ipe.ops.service.AuditService;

/** 产品与物模型。 */
@RestController
@RequestMapping("/api/ops/products")
public class ProductController {

    private final ProductService products;
    private final AuditService audits;

    public ProductController(ProductService products, AuditService audits) {
        this.products = products;
        this.audits = audits;
    }

    @GetMapping
    public PageResult<DeviceDtos.ProductRow> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return products.query(keyword, deviceType, categoryCode, page, size, sort);
    }

    @GetMapping("/all")
    public List<DeviceDtos.ProductRow> all() {
        return products.listAll();
    }

    /** 设备类型目录 + 每个类型的模板物模型，供「从模板新建产品」使用。 */
    @GetMapping("/device-types")
    public List<Map<String, Object>> deviceTypes() {
        return products.deviceTypeCatalog();
    }

    @GetMapping("/{id}")
    public DeviceDtos.ProductDetail detail(@PathVariable Long id) {
        return products.detail(id);
    }

    @GetMapping("/{id}/metadata")
    public Map<String, Object> metadata(@PathVariable Long id) {
        return products.metadata(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.ProductRow save(@RequestBody DeviceDtos.ProductSave input, Principal principal) {
        Product saved = products.save(input);
        audits.record(
                name(principal), "product.create", "product", String.valueOf(saved.id), "SUCCESS", saved.productCode);
        return products.row(saved);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDtos.ProductRow update(
            @PathVariable Long id, @RequestBody DeviceDtos.ProductSave input, Principal principal) {
        Product existing = products.get(id);
        DeviceDtos.ProductSave merged = new DeviceDtos.ProductSave(
                existing.productCode,
                input.name(),
                input.categoryCode(),
                existing.deviceType,
                input.protocol(),
                input.transport(),
                input.vendor(),
                input.model(),
                input.metadata(),
                input.configuration(),
                input.sceneModel(),
                input.description(),
                input.state());
        Product saved = products.save(merged);
        audits.record(name(principal), "product.update", "product", String.valueOf(id), "SUCCESS", saved.productCode);
        return products.row(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        products.delete(id);
        audits.record(name(principal), "product.delete", "product", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
