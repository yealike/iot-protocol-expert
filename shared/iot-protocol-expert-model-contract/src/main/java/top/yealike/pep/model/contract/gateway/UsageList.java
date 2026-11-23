package top.yealike.pep.model.contract.gateway;

import java.util.List;

/** 用量明细列表响应。 */
public record UsageList(List<UsageItem> items) {

    public UsageList {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
