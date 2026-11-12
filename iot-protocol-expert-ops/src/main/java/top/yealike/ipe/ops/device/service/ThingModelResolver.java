package top.yealike.ipe.ops.device.service;

import org.springframework.stereotype.Component;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.thingmodel.ThingModel;
import top.yealike.ipe.ops.thingmodel.ThingModelCodec;

/**
 * 物模型解析：产品模板 + 设备覆盖。
 *
 * <p>放在独立组件里，是为了让「设备服务」与「属性服务」都依赖它，而不是互相依赖。 合并语义在 {@link ThingModel#merge} 里：同名 id 整体替换，新 id 追加。
 */
@Component
public class ThingModelResolver {

    private final ProductService productService;
    private final DeviceInstanceRepository devices;

    public ThingModelResolver(ProductService productService, DeviceInstanceRepository devices) {
        this.productService = productService;
        this.devices = devices;
    }

    public ThingModel of(DeviceInstance device) {
        if (device == null) {
            return ThingModel.empty();
        }
        ThingModel base = productService.model(device.productId);
        if (device.deriveMetadata == null || device.deriveMetadata.isBlank()) {
            return base;
        }
        return base.merge(ThingModelCodec.parse(device.deriveMetadata));
    }

    public ThingModel of(Long deviceId) {
        return devices.findById(deviceId).map(this::of).orElse(ThingModel.empty());
    }
}
