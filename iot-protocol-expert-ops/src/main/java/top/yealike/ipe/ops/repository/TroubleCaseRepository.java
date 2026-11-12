package top.yealike.ipe.ops.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.TroubleCase;

public interface TroubleCaseRepository extends JpaRepository<TroubleCase, Long> {
    List<TroubleCase> findTop50ByOrderByIdDesc();

    List<TroubleCase> findTop50ByDeviceIdOrderByIdDesc(Long deviceId);
}
