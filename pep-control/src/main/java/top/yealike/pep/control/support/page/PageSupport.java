package top.yealike.pep.control.support.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 分页与排序参数的安全解析：限制单页上限，白名单排序字段。 */
public final class PageSupport {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;

    private PageSupport() {}

    public static PageQuery pageable(Integer page, Integer size, String sort, Set<String> allowedSortFields) {
        return pageable(page, size, sort, allowedSortFields, "-id", MAX_SIZE);
    }

    public static PageQuery pageable(
            Integer page, Integer size, String sort, Set<String> allowedSortFields, String defaultSort, int maxSize) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, maxSize);
        long offset = (long) pageIndex * pageSize;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页偏移量过大");
        }
        return new PageQuery(pageIndex, pageSize, (int) offset, orderBy(sort, allowedSortFields, defaultSort));
    }

    public record PageQuery(int page, int size, int offset, String orderBy) {}

    /** 支持 {@code field,asc|desc} 与简写 {@code -field}。非法字段直接忽略。 */
    private static String orderBy(String raw, Set<String> allowedSortFields, String defaultSort) {
        String effective = raw == null || raw.isBlank() ? defaultSort : raw;
        List<String> orders = new ArrayList<>();
        for (String token : effective.split(";")) {
            String text = token.trim();
            if (text.isEmpty()) {
                continue;
            }
            String direction = "ASC";
            if (text.startsWith("-")) {
                direction = "DESC";
                text = text.substring(1);
            }
            String[] parts = text.split(",");
            String field = parts[0].trim();
            if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())) {
                direction = "DESC";
            }
            if (!field.matches("[A-Za-z][A-Za-z0-9]*")
                    || (allowedSortFields != null && !allowedSortFields.contains(field))) {
                continue;
            }
            orders.add(column(field) + " " + direction);
        }
        if (orders.isEmpty()) {
            orders.add("id DESC");
        }
        return String.join(", ", orders);
    }

    private static String column(String field) {
        StringBuilder column = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char ch = field.charAt(i);
            if (Character.isUpperCase(ch)) {
                column.append('_').append(Character.toLowerCase(ch));
            } else {
                column.append(ch);
            }
        }
        return column.toString();
    }
}
