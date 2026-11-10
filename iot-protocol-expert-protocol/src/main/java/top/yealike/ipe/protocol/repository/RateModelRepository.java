package top.yealike.ipe.protocol.repository;

import top.yealike.ipe.protocol.domain.RateModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RateModelRepository extends JpaRepository<RateModelEntity, Long> {

    Optional<RateModelEntity> findByModelNo(String modelNo);

    Optional<RateModelEntity> findTopByActiveTrueOrderByUpdatedAtDesc();

    List<RateModelEntity> findAllByOrderByModelNoAsc();
}