package top.yealike.ipe.ai.mybatis;

import top.yealike.ipe.ai.contract.McpToolDescriptor;
import top.yealike.ipe.ai.support.JsonSupport;

public class McpToolDescriptorTypeHandler extends JsonTypeHandler<McpToolDescriptor> {
    @Override
    protected McpToolDescriptor parse(String value) {
        return JsonSupport.read(value, McpToolDescriptor.class);
    }
}
