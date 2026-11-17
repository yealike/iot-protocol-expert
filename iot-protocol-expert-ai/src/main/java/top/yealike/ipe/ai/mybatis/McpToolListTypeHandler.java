package top.yealike.ipe.ai.mybatis;

import java.util.List;
import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.support.JsonSupport;

public class McpToolListTypeHandler extends JsonTypeHandler<List<McpToolDescriptor>> {
    @Override
    protected List<McpToolDescriptor> parse(String value) {
        return JsonSupport.readList(value, McpToolDescriptor.class);
    }
}
