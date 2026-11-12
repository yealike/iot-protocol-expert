package top.yealike.ipe.ops.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 统一分页响应。
 *
 * <p>改造前设备列表直接返回 {@code List} 全量数据，前端无法翻页、后端无法限流。 所有集合型查询统一走这里，字段名与前端约定的 {@code
 * content/page/size/total/totalPages} 一致。
 */
public record PageResult<T>(List<T> content, int page, int size, long total, int totalPages) {

    public static <T> PageResult<T> of(Page<T> page) {
        return new PageResult<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <S, T> PageResult<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResult<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0, 0);
    }
}
