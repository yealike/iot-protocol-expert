package top.yealike.pep.ops.devices.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.devices.domain.DeviceChannel;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DevicePropertyLatest;
import top.yealike.pep.ops.devices.domain.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.domain.Product;
import top.yealike.pep.ops.devices.dto.DeviceRow;
import top.yealike.pep.ops.devices.dto.DeviceSave;
import top.yealike.pep.ops.devices.dto.ProtocolMappingInput;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.thingmodel.domain.EnumElement;
import top.yealike.pep.ops.devices.thingmodel.domain.EnumType;
import top.yealike.pep.ops.devices.thingmodel.domain.FloatType;
import top.yealike.pep.ops.devices.thingmodel.domain.IntType;
import top.yealike.pep.ops.devices.thingmodel.domain.PropertyMetadata;
import top.yealike.pep.ops.devices.thingmodel.domain.ThingModel;

class DeviceInstanceServiceTest {

    @Test
    void createsChargerWithManualSnAndGeneratedGuns() {
        Fixture fixture = fixture();
        Product product = product(1L, "YKC16", 2);
        when(fixture.products.get(1L)).thenReturn(product);
        when(fixture.devices.findByDeviceCode("10000008")).thenReturn(Optional.empty());
        when(fixture.devices.save(any(DeviceInstance.class))).thenAnswer(invocation -> {
            DeviceInstance device = invocation.getArgument(0);
            device.id = 88L;
            return device;
        });
        when(fixture.channels.findByDeviceIdAndChannelNo(88L, "01")).thenReturn(Optional.empty());
        when(fixture.channels.findByDeviceIdAndChannelNo(88L, "02")).thenReturn(Optional.empty());
        when(fixture.channels.save(any(DeviceChannel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceInstance created = fixture.service.create(new DeviceSave("10000008", "测试桩", 1L, true, null));

        assertEquals("10000008", created.deviceCode);
        assertEquals("YKC16", created.protocol);
        ArgumentCaptor<DeviceChannel> channelCaptor = ArgumentCaptor.forClass(DeviceChannel.class);
        verify(fixture.channels, times(2)).save(channelCaptor.capture());
        assertEquals(
                List.of("1000000801", "1000000802"),
                channelCaptor.getAllValues().stream()
                        .map(channel -> channel.platformGunNo)
                        .toList());
        verify(fixture.devices, never()).selectNextDeviceSnCandidate();
    }

    @Test
    void rejectsDuplicateManualSnBeforeWriting() {
        Fixture fixture = fixture();
        when(fixture.products.get(1L)).thenReturn(product(1L, "YKC16", 1));
        when(fixture.devices.findByDeviceCode("10000008")).thenReturn(Optional.of(device(10L, "10000008", "YKC16")));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.create(new DeviceSave("10000008", "测试桩", 1L, true, null)));

        assertEquals("设备 SN 已存在: 10000008", error.getMessage());
        verify(fixture.devices, never()).save(any(DeviceInstance.class));
    }

    @Test
    void normalizesGunAndProtocolConnectorNumbersWhenSavingMappings() {
        Fixture fixture = fixture();
        DeviceInstance device = device(10L, "10000000", "OCPP16J");
        Product product = product(1L, "OCPP16J", 2);
        DeviceChannel firstGun = channel(10L, "01");
        DeviceChannel secondGun = channel(10L, "02");
        when(fixture.mappings.findByDeviceId(10L)).thenReturn(Optional.empty());
        when(fixture.mappings.findByProtocolAndThirdPartyDeviceId("OCPP16J", "CP-001"))
                .thenReturn(Optional.empty());
        when(fixture.mappings.save(any(DeviceProtocolMapping.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.channels.findByDeviceIdOrderByChannelNoAsc(10L)).thenReturn(List.of(firstGun, secondGun));
        when(fixture.channels.save(any(DeviceChannel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.replaceMappings(
                device,
                product,
                List.of(
                        new ProtocolMappingInput("1", "CP-001", null, true),
                        new ProtocolMappingInput("02", "CP-001", "7", true)));

        ArgumentCaptor<DeviceProtocolMapping> mappingCaptor = ArgumentCaptor.forClass(DeviceProtocolMapping.class);
        verify(fixture.mappings).save(mappingCaptor.capture());
        assertEquals("CP-001", mappingCaptor.getValue().thirdPartyDeviceId);
        assertEquals("01", firstGun.protocolConnectorNo);
        assertEquals("07", secondGun.protocolConnectorNo);
    }

    @Test
    void rejectsPartialConnectorMappingBeforeWriting() {
        Fixture fixture = fixture();
        DeviceInstance device = device(10L, "10000000", "OCPP16J");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.replaceMappings(
                        device,
                        product(1L, "OCPP16J", 2),
                        List.of(new ProtocolMappingInput("01", "CP-001", "1", true))));

        assertEquals("配置第三方设备号时必须完整映射全部 2 把充电枪", error.getMessage());
        verify(fixture.mappings, never()).save(any(DeviceProtocolMapping.class));
    }

    @Test
    void rejectsProtocolConnectorOutsideGatewayRange() {
        Fixture fixture = fixture();
        DeviceInstance device = device(10L, "10000000", "OCPP16J");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.replaceMappings(
                        device,
                        product(1L, "OCPP16J", 1),
                        List.of(new ProtocolMappingInput("01", "CP-001", "100", true))));

        assertEquals("第三方枪号必须为 1 至 99: 100", error.getMessage());
        verify(fixture.mappings, never()).save(any(DeviceProtocolMapping.class));
    }

    @Test
    void rejectsConflictingEnabledFlagsAcrossGuns() {
        Fixture fixture = fixture();
        DeviceInstance device = device(10L, "10000000", "OCPP16J");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.replaceMappings(
                        device,
                        product(1L, "OCPP16J", 2),
                        List.of(
                                new ProtocolMappingInput("01", "CP-001", "1", true),
                                new ProtocolMappingInput("02", "CP-001", "2", false))));

        assertEquals("同一台充电桩的映射启用状态必须一致", error.getMessage());
        verify(fixture.mappings, never()).save(any(DeviceProtocolMapping.class));
    }

    @Test
    void showsChannelMetricsForTheActiveGun() {
        Fixture fixture = fixture();
        DeviceInstance device = device(10L, "10000000", "YKC16");
        device.productId = 1L;
        DeviceChannel firstGun = channel(10L, "01");
        DeviceChannel secondGun = channel(10L, "02");
        ThingModel model = new ThingModel(
                List.of(
                        PropertyMetadata.channel(
                                "gunState",
                                "枪状态",
                                new EnumType(
                                        List.of(new EnumElement("idle", "空闲"), new EnumElement("charging", "充电中")))),
                        PropertyMetadata.channel("soc", "电池 SOC", new IntType(0L, 100L, "%")),
                        PropertyMetadata.channel("chargeEnergy", "本次电量", new FloatType(0D, null, "kWh", 2))),
                List.of(),
                List.of(),
                List.of());
        LocalDateTime sampledAt = LocalDateTime.of(2026, 11, 22, 10, 0);

        when(fixture.products.findAllById(any())).thenReturn(List.of(product(1L, "YKC16", 2)));
        when(fixture.products.protocolDocuments(any())).thenReturn(Map.of());
        when(fixture.channels.findByDeviceIdIn(any())).thenReturn(List.of(firstGun, secondGun));
        when(fixture.latest.findByDeviceIdIn(any()))
                .thenReturn(List.of(
                        property(10L, "01", "gunState", "enum", null, "charging", sampledAt),
                        property(10L, "02", "gunState", "enum", null, "idle", sampledAt),
                        property(10L, "01", "soc", "int", new BigDecimal("64"), null, sampledAt),
                        property(10L, "01", "chargeEnergy", "float", new BigDecimal("12.5"), null, sampledAt)));
        when(fixture.mappings.selectByDeviceIds(any())).thenReturn(List.of());
        when(fixture.models.of(device)).thenReturn(model);

        DeviceRow row = fixture.service.view(device);

        assertEquals(
                Map.of(
                        "01|gunState", "01枪 · 充电中",
                        "02|gunState", "02枪 · 空闲",
                        "01|soc", "01枪 · 64 %",
                        "01|chargeEnergy", "01枪 · 12.50 kWh"),
                row.keyMetrics());
    }

    private static Fixture fixture() {
        DeviceInstanceMapper devices = mock(DeviceInstanceMapper.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        DeviceEventLogMapper events = mock(DeviceEventLogMapper.class);
        DevicePropertyLatestMapper latest = mock(DevicePropertyLatestMapper.class);
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        ProductService products = mock(ProductService.class);
        ThingModelResolver models = mock(ThingModelResolver.class);
        DevicePropertyService properties = mock(DevicePropertyService.class);
        CommandRecordMapper commands = mock(CommandRecordMapper.class);
        return new Fixture(
                new DeviceInstanceService(
                        devices, channels, events, latest, mappings, products, models, properties, commands),
                devices,
                channels,
                latest,
                mappings,
                products,
                models);
    }

    private static Product product(long id, String protocol, int connectorCount) {
        Product product = new Product();
        product.id = id;
        product.name = "测试产品";
        product.protocol = protocol;
        product.state = "PUBLISHED";
        product.connectorCount = connectorCount;
        return product;
    }

    private static DeviceInstance device(long id, String sn, String protocol) {
        DeviceInstance device = new DeviceInstance();
        device.id = id;
        device.deviceCode = sn;
        device.name = "测试桩";
        device.protocol = protocol;
        device.enabled = true;
        return device;
    }

    private static DeviceChannel channel(long deviceId, String channelNo) {
        DeviceChannel channel = new DeviceChannel();
        channel.deviceId = deviceId;
        channel.channelNo = channelNo;
        channel.platformGunNo = "10000000" + channelNo;
        return channel;
    }

    private static DevicePropertyLatest property(
            long deviceId,
            String channelNo,
            String propertyId,
            String valueType,
            BigDecimal valueNum,
            String valueText,
            LocalDateTime valueTime) {
        DevicePropertyLatest property = new DevicePropertyLatest(deviceId, channelNo, propertyId, valueType);
        property.valueNum = valueNum;
        property.valueText = valueText;
        property.valueTime = valueTime;
        return property;
    }

    private record Fixture(
            DeviceInstanceService service,
            DeviceInstanceMapper devices,
            DeviceChannelMapper channels,
            DevicePropertyLatestMapper latest,
            DeviceProtocolMappingMapper mappings,
            ProductService products,
            ThingModelResolver models) {}
}
