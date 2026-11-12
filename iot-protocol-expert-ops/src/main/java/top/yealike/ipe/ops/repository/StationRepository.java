package top.yealike.ipe.ops.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.Station;

public interface StationRepository extends JpaRepository<Station, Long> {
    Optional<Station> findByStationCode(String stationCode);
}
