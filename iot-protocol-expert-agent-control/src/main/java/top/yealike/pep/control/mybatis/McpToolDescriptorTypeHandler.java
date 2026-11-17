package top.yealike.pep.control.mybatis;

import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

public class McpToolDescriptorTypeHandler extends JsonTypeHandler<McpToolDescriptor> {
    @Override
    protected McpToolDescriptor parse(String value) {
        return JsonSupport.read(value, McpToolDescriptor.class);
    }
}
