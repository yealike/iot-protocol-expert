package top.yealike.ipe.ops.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.domain.RateModel;

public interface RateModelMapper {

    RateModel selectById(@Param("id") Long id);

    RateModel selectByModelNo(@Param("modelNo") String modelNo);

    RateModel selectTopActive();

    List<RateModel> selectAll();

    int insert(RateModel model);

    int update(RateModel model);

    default Optional<RateModel> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<RateModel> findByModelNo(String modelNo) {
        return Optional.ofNullable(selectByModelNo(modelNo));
    }

    default Optional<RateModel> findTopByActiveTrueOrderByUpdatedAtDesc() {
        return Optional.ofNullable(selectTopActive());
    }

    default List<RateModel> findAll() {
        return selectAll();
    }

    default RateModel save(RateModel model) {
        if (model.id == null) {
            insert(model);
        } else {
            update(model);
        }
        return model;
    }
}
