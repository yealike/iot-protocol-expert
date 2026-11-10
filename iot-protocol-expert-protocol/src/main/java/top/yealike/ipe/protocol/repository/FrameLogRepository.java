package top.yealike.ipe.protocol.repository;

import top.yealike.ipe.protocol.domain.FrameLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FrameLogRepository extends JpaRepository<FrameLogEntity, Long> {

    List<FrameLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<FrameLogEntity> findByPileCodeOrderByCreatedAtDesc(String pileCode, Pageable pageable);

    long countByDirection(String direction);
}