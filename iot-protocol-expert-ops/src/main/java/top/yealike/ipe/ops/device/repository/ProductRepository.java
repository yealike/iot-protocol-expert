package top.yealike.ipe.ops.device.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import top.yealike.ipe.ops.device.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByProductCode(String productCode);

    List<Product> findByDeviceTypeAndState(String deviceType, String state);

    List<Product> findByCategoryCode(String categoryCode);

    List<Product> findByProtocolAndState(String protocol, String state);

    List<Product> findAllByOrderByIdAsc();
}
