package top.yealike.ipe.ops.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 分页与排序参数的安全解析：限制单页上限，白名单排序字段。 */
public final class PageSupport {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;

    private PageSupport() {}

    public static Pageable pageable(Integer page, Integer size, String sort, Set<String> allowedSortFields) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(pageIndex, pageSize, sort(sort, allowedSortFields));
    }

    /** 支持 {@code field,asc|desc} 与简写 {@code -field}。非法字段直接忽略。 */
    public static Sort sort(String raw, Set<String> allowedSortFields) {
        if (raw == null || raw.isBlank()) {
            return Sort.by(Sort.Order.desc("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String token : raw.split(";")) {
            String text = token.trim();
            if (text.isEmpty()) {
                continue;
            }
            Sort.Direction direction = Sort.Direction.ASC;
            if (text.startsWith("-")) {
                direction = Sort.Direction.DESC;
                text = text.substring(1);
            }
            String[] parts = text.split(",");
            String field = parts[0].trim();
            if (parts.length > 1) {
                direction = "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC;
            }
            if (allowedSortFields == null || allowedSortFields.contains(field)) {
                orders.add(new Sort.Order(direction, field));
            }
        }
        return orders.isEmpty() ? Sort.by(Sort.Order.desc("id")) : Sort.by(orders);
    }
}
