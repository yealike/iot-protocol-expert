package top.yealike.ipe.ai.mybatis;

import java.util.List;
import top.yealike.ipe.ai.conversation.ConversationRecords;
import top.yealike.ipe.ai.support.JsonSupport;

public class CitationListTypeHandler extends JsonTypeHandler<List<ConversationRecords.Citation>> {
    @Override
    protected List<ConversationRecords.Citation> parse(String value) {
        return value == null ? List.of() : JsonSupport.readList(value, ConversationRecords.Citation.class);
    }
}
