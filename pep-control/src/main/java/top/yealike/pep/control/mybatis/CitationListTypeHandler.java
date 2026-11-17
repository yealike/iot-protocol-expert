package top.yealike.pep.control.mybatis;

import java.util.List;
import top.yealike.pep.control.conversations.ConversationRecords;
import top.yealike.pep.control.support.JsonSupport;

public class CitationListTypeHandler extends JsonTypeHandler<List<ConversationRecords.Citation>> {
    @Override
    protected List<ConversationRecords.Citation> parse(String value) {
        return value == null ? List.of() : JsonSupport.readList(value, ConversationRecords.Citation.class);
    }
}
