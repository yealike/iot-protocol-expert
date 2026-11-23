package top.yealike.pep.control.conversations.mapper;

import java.util.List;
import top.yealike.pep.control.mybatis.JsonTypeHandler;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.RunCitation;

public class CitationListTypeHandler extends JsonTypeHandler<List<RunCitation>> {
    @Override
    protected List<RunCitation> parse(String value) {
        return value == null ? List.of() : JsonSupport.readList(value, RunCitation.class);
    }
}
