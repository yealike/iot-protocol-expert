package top.yealike.pep.control.conversations.mapper;

import java.util.List;
import top.yealike.pep.control.conversations.domain.Citation;
import top.yealike.pep.control.mybatis.JsonTypeHandler;
import top.yealike.pep.control.support.JsonSupport;

public class CitationListTypeHandler extends JsonTypeHandler<List<Citation>> {
    @Override
    protected List<Citation> parse(String value) {
        return value == null ? List.of() : JsonSupport.readList(value, Citation.class);
    }
}
