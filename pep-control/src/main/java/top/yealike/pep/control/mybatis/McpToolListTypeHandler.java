package top.yealike.pep.control.mybatis;

import java.util.List;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.control.support.JsonSupport;

public class McpToolListTypeHandler extends JsonTypeHandler<List<McpToolDescriptor>> {
    @Override
    protected List<McpToolDescriptor> parse(String value) {
        return JsonSupport.readList(value, McpToolDescriptor.class);
    }
}
