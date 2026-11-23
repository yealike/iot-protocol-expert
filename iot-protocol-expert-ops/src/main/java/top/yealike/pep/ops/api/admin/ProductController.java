package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.audit.service.AuditService;
import top.yealike.pep.ops.devices.domain.Product;
import top.yealike.pep.ops.devices.dto.ProductDetail;
import top.yealike.pep.ops.devices.dto.ProductRow;
import top.yealike.pep.ops.devices.dto.ProductSave;
import top.yealike.pep.ops.devices.service.ProductService;
import top.yealike.pep.ops.support.page.PageResult;

/** 充电桩产品与物模型。 */
@RestController
@RequestMapping(path = "/api/ops/products", produces = "application/json")
@Tag(name = "充电桩产品与物模型", description = "产品协议、标准枪数和物模型配置")
@SecurityRequirement(name = "sessionCookie")
public class ProductController {

    private final ProductService products;
    private final AuditService audits;

    public ProductController(ProductService products, AuditService audits) {
        this.products = products;
        this.audits = audits;
    }

    @GetMapping
    @Operation(operationId = "queryProducts", summary = "分页查询产品")
    public PageResult<ProductRow> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return products.query(keyword, categoryCode, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getProduct", summary = "查询产品详情")
    public ProductDetail detail(@PathVariable Long id) {
        return products.detail(id);
    }

    @PostMapping
    @Operation(operationId = "createProduct", summary = "创建产品")
    @PreAuthorize("hasAuthority('ops:manage')")
    public ProductRow save(@RequestBody ProductSave input, Principal principal) {
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
    @Operation(operationId = "updateProduct", summary = "更新产品")
    @PreAuthorize("hasAuthority('ops:manage')")
    public ProductRow update(@PathVariable Long id, @RequestBody ProductSave input, Principal principal) {
        Product existing = products.get(id);
        ProductSave merged = new ProductSave(
                existing.productCode,
                input.name(),
                input.categoryCode(),
                input.protocolDocumentId(),
                input.transport(),
                input.vendor(),
                input.model(),
                input.connectorCount(),
                input.metadata(),
                input.configuration(),
                input.description(),
                input.state());
        Product saved = products.save(merged);
        audits.record(name(principal), "product.update", "product", String.valueOf(id), "SUCCESS", saved.productCode);
        return products.row(saved);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteProduct", summary = "删除产品")
    @PreAuthorize("hasAuthority('ops:manage')")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        products.delete(id);
        audits.record(name(principal), "product.delete", "product", String.valueOf(id), "SUCCESS", null);
        return Map.of("deleted", true);
    }

    private static String name(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
