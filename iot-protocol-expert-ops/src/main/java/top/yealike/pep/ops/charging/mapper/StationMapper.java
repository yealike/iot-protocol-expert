package top.yealike.pep.ops.charging.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.charging.domain.Station;

public interface StationMapper {

    Station selectById(@Param("id") Long id);

    Station selectByStationCode(@Param("stationCode") String stationCode);

    List<Station> selectAll();

    List<Station> selectByIds(@Param("ids") Collection<Long> ids);

    long count();

    long countById(@Param("id") Long id);

    int insert(Station station);

    int update(Station station);

    int deleteById(@Param("id") Long id);

    default Optional<Station> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<Station> findByStationCode(String stationCode) {
        return Optional.ofNullable(selectByStationCode(stationCode));
    }

    default List<Station> findAll() {
        return selectAll();
    }

    default List<Station> findAllById(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : selectByIds(ids);
    }

    default Station save(Station station) {
        if (station.id == null) {
            insert(station);
        } else {
            update(station);
        }
        return station;
    }

    default void delete(Station station) {
        if (station != null && station.id != null) {
            deleteById(station.id);
        }
    }
}
