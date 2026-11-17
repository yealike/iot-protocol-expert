package top.yealike.pep.control.mybatis;

import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.control.support.JsonSupport;

public class McpToolDescriptorTypeHandler extends JsonTypeHandler<McpToolDescriptor> {
    @Override
    protected McpToolDescriptor parse(String value) {
        return JsonSupport.read(value, McpToolDescriptor.class);
    }
}
