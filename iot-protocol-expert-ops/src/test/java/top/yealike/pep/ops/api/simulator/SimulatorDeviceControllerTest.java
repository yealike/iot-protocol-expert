package top.yealike.pep.ops.api.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.yealike.pep.ops.devices.domain.DeviceChannel;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.dto.DeviceRow;
import top.yealike.pep.ops.devices.dto.SimulatorDeviceView;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;
import top.yealike.pep.ops.devices.service.DeviceService;

class SimulatorDeviceControllerTest {

    @Test
    void listReturnsAllConnectorMappingsUsingBatchQueries() {
        DeviceService devices = mock(DeviceService.class);
        DeviceInstanceService instances = mock(DeviceInstanceService.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        SimulatorDeviceController controller = new SimulatorDeviceController(devices, instances, channels, mappings);
        DeviceInstance first = device(10L, "10000000", "YKC16");
        DeviceInstance second = device(20L, "10000001", "OCPP16J");
        when(devices.simulatorDevices()).thenReturn(List.of(first, second));
        when(mappings.selectByDeviceIds(List.of(10L, 20L)))
                .thenReturn(List.of(mapping(10L, "YKC16", "32010600000001"), mapping(20L, "OCPP16J", "CP-001")));
        when(instances.rows(List.of(first, second))).thenReturn(List.of(row(first, "ONLINE"), row(second, "OFFLINE")));
        when(channels.findByDeviceIdIn(List.of(10L, 20L)))
                .thenReturn(List.of(
                        channel(10L, "01", "1000000001", "1"),
                        channel(10L, "02", "1000000002", "2"),
                        channel(20L, "01", "1000000101", "1")));

        List<SimulatorDeviceView> result = controller.list(null, null, null);

        assertEquals(2, result.size());
        assertEquals("32010600000001", result.get(0).protocolDeviceId());
        assertEquals(2, result.get(0).connectors().size());
        assertEquals("1000000002", result.get(0).connectors().get(1).platformGunNo());
        assertEquals("2", result.get(0).connectors().get(1).protocolConnectorNo());
        assertEquals("1000000101", result.get(1).connectors().get(0).platformGunNo());
        verify(mappings, times(1)).selectByDeviceIds(List.of(10L, 20L));
        verify(mappings, never()).selectByDeviceId(anyLong());
        verify(channels, times(1)).findByDeviceIdIn(List.of(10L, 20L));
        verify(channels, never()).selectByDeviceIdOrderByChannelNoAsc(anyLong());
    }

    @Test
    void listFiltersByThirdPartyDeviceIdFromBatchMappings() {
        DeviceService devices = mock(DeviceService.class);
        DeviceInstanceService instances = mock(DeviceInstanceService.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        SimulatorDeviceController controller = new SimulatorDeviceController(devices, instances, channels, mappings);
        DeviceInstance first = device(10L, "10000000", "YKC16");
        DeviceInstance second = device(20L, "10000001", "OCPP16J");
        when(devices.simulatorDevices()).thenReturn(List.of(first, second));
        when(mappings.selectByDeviceIds(List.of(10L, 20L)))
                .thenReturn(List.of(mapping(10L, "YKC16", "32010600000001"), mapping(20L, "OCPP16J", "CP-001")));
        when(instances.rows(List.of(second))).thenReturn(List.of(row(second, "ONLINE")));
        when(channels.findByDeviceIdIn(List.of(20L))).thenReturn(List.of(channel(20L, "01", "1000000101", "1")));

        List<SimulatorDeviceView> result = controller.list(null, null, "CP-001");

        assertEquals(1, result.size());
        assertEquals("10000001", result.get(0).sn());
        verify(mappings, times(1)).selectByDeviceIds(List.of(10L, 20L));
        verify(channels, times(1)).findByDeviceIdIn(List.of(20L));
    }

    @Test
    void listReturnsEmptyConnectorArrayWhenNoMappingIsConfigured() {
        DeviceService devices = mock(DeviceService.class);
        DeviceInstanceService instances = mock(DeviceInstanceService.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        SimulatorDeviceController controller = new SimulatorDeviceController(devices, instances, channels, mappings);
        DeviceInstance device = device(10L, "10000000", "YKC16");
        when(devices.simulatorDevices()).thenReturn(List.of(device));
        when(mappings.selectByDeviceIds(List.of(10L))).thenReturn(List.of());
        when(instances.rows(List.of(device))).thenReturn(List.of(row(device, "OFFLINE")));
        when(channels.findByDeviceIdIn(List.of(10L))).thenReturn(List.of());

        SimulatorDeviceView result = controller.list(null, null, null).get(0);

        assertEquals(List.of(), result.connectors());
    }

    @Test
    void detailReturnsConnectorMappingsAndSimulationFlags() {
        DeviceService devices = mock(DeviceService.class);
        DeviceInstanceService instances = mock(DeviceInstanceService.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        SimulatorDeviceController controller = new SimulatorDeviceController(devices, instances, channels, mappings);
        DeviceInstance device = device(20L, "10000001", "OCPP16J");
        when(devices.get(20L)).thenReturn(device);
        when(mappings.findByDeviceId(20L)).thenReturn(Optional.of(mapping(20L, "OCPP16J", "CP-001")));
        when(channels.findByDeviceIdOrderByChannelNoAsc(20L))
                .thenReturn(List.of(channel(20L, "01", "1000000101", "7")));
        when(instances.view(device)).thenReturn(row(device, "FAULT"));

        SimulatorDeviceView result = controller.get(20L);

        assertTrue(result.online());
        assertTrue(result.allowSimulate());
        assertEquals("CP-001", result.protocolDeviceId());
        assertEquals("1000000101", result.connectors().get(0).platformGunNo());
        assertEquals("7", result.connectors().get(0).protocolConnectorNo());
        verify(channels, never()).findByDeviceIdIn(org.mockito.ArgumentMatchers.anyList());
    }

    private static DeviceInstance device(long id, String sn, String protocol) {
        DeviceInstance device = new DeviceInstance();
        device.id = id;
        device.deviceCode = sn;
        device.name = "充电桩 " + sn;
        device.protocol = protocol;
        device.state = "OFFLINE";
        device.enabled = true;
        device.allowSimulate = true;
        return device;
    }

    private static DeviceRow row(DeviceInstance device, String state) {
        return new DeviceRow(
                device.id,
                device.deviceCode,
                device.name,
                device.productId,
                "测试产品",
                device.protocol,
                device.protocol,
                "1.6",
                state,
                device.enabled,
                Map.of(),
                null,
                2,
                null);
    }

    private static DeviceProtocolMapping mapping(long deviceId, String protocol, String thirdPartyId) {
        DeviceProtocolMapping mapping = new DeviceProtocolMapping();
        mapping.deviceId = deviceId;
        mapping.protocol = protocol;
        mapping.thirdPartyDeviceId = thirdPartyId;
        mapping.enabled = true;
        return mapping;
    }

    private static DeviceChannel channel(
            long deviceId, String channelNo, String platformGunNo, String protocolConnectorNo) {
        DeviceChannel channel = new DeviceChannel();
        channel.deviceId = deviceId;
        channel.channelNo = channelNo;
        channel.platformGunNo = platformGunNo;
        channel.protocolConnectorNo = protocolConnectorNo;
        return channel;
    }
}
