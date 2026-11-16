package top.yealike.ipe.ops.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.device.entity.Product;

public interface ProductMapper {

    Product selectById(@Param("id") Long id);

    Product selectByProductCode(@Param("productCode") String productCode);

    List<Product> selectAllOrderByIdAsc();

    List<Product> selectByIds(@Param("ids") Collection<Long> ids);

    List<Product> selectByProtocolAndState(@Param("protocol") String protocol, @Param("state") String state);

    List<Product> search(
            @Param("keyword") String keyword,
            @Param("deviceType") String deviceType,
            @Param("categoryCode") String categoryCode,
            @Param("orderBy") String orderBy,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countSearch(
            @Param("keyword") String keyword,
            @Param("deviceType") String deviceType,
            @Param("categoryCode") String categoryCode);

    long count();

    int insert(Product product);

    int update(Product product);

    int deleteById(@Param("id") Long id);

    default Optional<Product> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<Product> findByProductCode(String productCode) {
        return Optional.ofNullable(selectByProductCode(productCode));
    }

    default List<Product> findAll() {
        return selectAllOrderByIdAsc();
    }

    default List<Product> findAllById(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : selectByIds(ids);
    }

    default List<Product> findByProtocolAndState(String protocol, String state) {
        return selectByProtocolAndState(protocol, state);
    }

    default Product save(Product product) {
        if (product.id == null) {
            insert(product);
        } else {
            update(product);
        }
        return product;
    }

    default void delete(Product product) {
        if (product != null && product.id != null) {
            deleteById(product.id);
        }
    }
}
