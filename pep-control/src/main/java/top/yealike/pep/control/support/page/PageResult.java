package top.yealike.pep.control.support.page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应。
 *
 * <p>改造前设备列表直接返回 {@code List} 全量数据，前端无法翻页、后端无法限流。 所有集合型查询统一走这里，字段名与前端约定的 {@code
 * content/page/size/total/totalPages} 一致。
 */
public record PageResult<T>(List<T> content, int page, int size, long total, int totalPages) {

    public static <T> PageResult<T> of(List<T> content, int page, int size, long total) {
        return new PageResult<>(content, page, size, total, totalPages(total, size));
    }

    public static <S, T> PageResult<T> of(List<S> content, int page, int size, long total, Function<S, T> mapper) {
        return new PageResult<>(content.stream().map(mapper).toList(), page, size, total, totalPages(total, size));
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0, 0);
    }

    private static int totalPages(long total, int size) {
        return size <= 0 || total <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
