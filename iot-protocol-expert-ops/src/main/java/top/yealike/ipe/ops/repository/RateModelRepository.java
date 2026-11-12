package top.yealike.ipe.ops.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.RateModel;

public interface RateModelRepository extends JpaRepository<RateModel, Long> {
    Optional<RateModel> findByModelNo(String modelNo);

    Optional<RateModel> findTopByActiveTrueOrderByUpdatedAtDesc();
}
