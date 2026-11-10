package top.yealike.ipe.protocol.repository;

import top.yealike.ipe.protocol.domain.GunStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GunStateRepository extends JpaRepository<GunStateEntity, Long> {

    Optional<GunStateEntity> findByPileCodeAndGunNo(String pileCode, String gunNo);

    List<GunStateEntity> findByPileCodeOrderByGunNoAsc(String pileCode);
}