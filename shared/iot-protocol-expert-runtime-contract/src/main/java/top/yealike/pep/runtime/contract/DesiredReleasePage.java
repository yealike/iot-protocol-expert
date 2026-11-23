package top.yealike.pep.runtime.contract;

import java.util.List;

/** Control desired-releases 接口的分页响应。 */
public record DesiredReleasePage(List<DesiredRelease> items, long cursor, int limit, boolean hasMore) {

    public DesiredReleasePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
