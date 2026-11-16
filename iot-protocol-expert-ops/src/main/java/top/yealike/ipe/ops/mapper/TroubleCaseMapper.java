package top.yealike.ipe.ops.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.domain.TroubleCase;

public interface TroubleCaseMapper {

    List<TroubleCase> selectTop(@Param("limit") int limit);

    List<TroubleCase> selectTopByDevice(@Param("deviceId") Long deviceId, @Param("limit") int limit);

    int insert(TroubleCase troubleCase);

    default List<TroubleCase> findTop50ByOrderByIdDesc() {
        return selectTop(50);
    }

    default List<TroubleCase> findTop50ByDeviceIdOrderByIdDesc(Long deviceId) {
        return selectTopByDevice(deviceId, 50);
    }

    default TroubleCase save(TroubleCase troubleCase) {
        insert(troubleCase);
        return troubleCase;
    }
}
