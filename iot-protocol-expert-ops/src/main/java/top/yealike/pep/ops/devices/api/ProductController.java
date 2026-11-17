package top.yealike.pep.ops.devices.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.audit.AuditService;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.Product;
import top.yealike.pep.ops.devices.service.ProductService;
import top.yealike.pep.ops.support.page.PageResult;

/** 产品与物模型。 */
@RestController
@RequestMapping("/api/ops/products")
@Tag(name = "产品与物模型", description = "设备产品档案、协议绑定和物模型配置")
public class ProductController {

    private final ProductService products;
    private final AuditService audits;

    public ProductController(ProductService products, AuditService audits) {
        this.products = products;
        this.audits = audits;
    }

    @GetMapping
    @Operation(summary = "分页查询产品")
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
    @Operation(summary = "查询全部产品")
    public List<DeviceDtos.ProductRow> all() {
        return products.listAll();
    }

    /** 设备类型目录 + 每个类型的模板物模型，供「从模板新建产品」使用。 */
    @GetMapping("/device-types")
    @Operation(summary = "查询设备类型模板")
    public List<Map<String, Object>> deviceTypes() {
        return products.deviceTypeCatalog();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询产品详情")
    public DeviceDtos.ProductDetail detail(@Parameter(description = "产品 ID") @PathVariable Long id) {
        return products.detail(id);
    }

    @GetMapping("/{id}/metadata")
    @Operation(summary = "查询产品元数据")
    public Map<String, Object> metadata(@Parameter(description = "产品 ID") @PathVariable Long id) {
        return products.metadata(id);
    }

    @PostMapping
    @Operation(summary = "创建产品")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.ProductRow save(@RequestBody DeviceDtos.ProductSave input, Principal principal) {
        if (input.productCode() != null
                && products.findByCode(input.productCode().trim()).isPresent()) {
            throw new IllegalArgumentException("产品编码已存在");
        }
        Product saved = products.save(input);
        audits.record(
                name(principal), "product.create", "product", String.valueOf(saved.id), "SUCCESS", saved.productCode);
        return products.row(saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新产品")
    @PreAuthorize("hasAuthority('ops:manage')")
    public DeviceDtos.ProductRow update(
            @PathVariable Long id, @RequestBody DeviceDtos.ProductSave input, Principal principal) {
        Product existing = products.get(id);
        DeviceDtos.ProductSave merged = new DeviceDtos.ProductSave(
                existing.productCode,
                input.name(),
                input.categoryCode(),
                existing.deviceType,
                input.protocol(),
                input.protocolDocumentId(),
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
    @Operation(summary = "删除产品")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> delete(@Parameter(description = "产品 ID") @PathVariable Long id, Principal principal) {
        products.delete(id);
        audits.record(name(principal), "product.delete", "product", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
