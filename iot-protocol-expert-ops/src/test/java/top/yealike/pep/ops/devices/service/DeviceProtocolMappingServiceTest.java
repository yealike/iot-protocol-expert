package top.yealike.pep.ops.devices.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.entity.DeviceChannel;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;

class DeviceProtocolMappingServiceTest {

    @Test
    void returnsStableCursorPageWithPlatformGunMappings() {
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        DeviceInstanceMapper devices = mock(DeviceInstanceMapper.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        DeviceProtocolMapping first = mapping(1, 10, "YKC16", "32010600000001");
        DeviceProtocolMapping second = mapping(2, 20, "OCPP16J", "CP-001");
        DeviceProtocolMapping third = mapping(3, 30, "YKC16", "32010600000003");
        when(mappings.selectPage(0L, 3)).thenReturn(List.of(first, second, third));
        when(devices.findAllById(List.of(10L, 20L)))
                .thenReturn(List.of(device(10, "10000000", "YKC16"), device(20, "10000001", "OCPP16J")));
        DeviceChannel ykcGun = channel(10, "01", "1000000001", "1");
        DeviceChannel ocppGun = channel(20, "02", "1000000102", "2");
        when(channels.findByDeviceIdIn(List.of(10L, 20L))).thenReturn(List.of(ykcGun, ocppGun));

        DeviceDtos.DeviceMappingPage page = new DeviceProtocolMappingService(mappings, devices, channels).page(0L, 2);

        assertEquals(2, page.content().size());
        assertTrue(page.hasMore());
        assertEquals(2L, page.nextAfterId());
        DeviceDtos.DeviceMappingRow firstRow = page.content().get(0);
        assertEquals("10000000", firstRow.deviceSn());
        assertEquals("YKC16", firstRow.protocol());
        assertEquals("32010600000001", firstRow.thirdPartyDeviceId());
        assertEquals("1000000001", firstRow.connectors().get(0).platformGunNo());
        assertEquals("1", firstRow.connectors().get(0).protocolConnectorNo());
        assertEquals("1000000102", page.content().get(1).connectors().get(0).platformGunNo());
    }

    @Test
    void emptyPageDoesNotTryToResolveDevices() {
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        DeviceInstanceMapper devices = mock(DeviceInstanceMapper.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        when(mappings.selectPage(0L, 201)).thenReturn(List.of());

        DeviceDtos.DeviceMappingPage page =
                new DeviceProtocolMappingService(mappings, devices, channels).page(-1L, 200);

        assertTrue(page.content().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextAfterId());
        verify(devices, never()).findAllById(org.mockito.ArgumentMatchers.anyList());
        verify(channels, never()).findByDeviceIdIn(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsProtocolMismatchAndMissingDevices() {
        DeviceProtocolMappingMapper mappings = mock(DeviceProtocolMappingMapper.class);
        DeviceInstanceMapper devices = mock(DeviceInstanceMapper.class);
        DeviceChannelMapper channels = mock(DeviceChannelMapper.class);
        when(mappings.selectPage(0L, 2))
                .thenReturn(List.of(mapping(1, 10, "OCPP16J", "CP-001")))
                .thenReturn(List.of(mapping(2, 20, "YKC16", "32010600000002")));
        when(devices.findAllById(List.of(10L))).thenReturn(List.of(device(10, "10000000", "YKC16")));
        when(devices.findAllById(List.of(20L))).thenReturn(List.of());
        when(channels.findByDeviceIdIn(List.of(10L))).thenReturn(List.of());
        when(channels.findByDeviceIdIn(List.of(20L))).thenReturn(List.of());

        DeviceProtocolMappingService service = new DeviceProtocolMappingService(mappings, devices, channels);

        assertThrows(IllegalStateException.class, () -> service.page(0L, 1));
        assertThrows(IllegalStateException.class, () -> service.page(0L, 1));
    }

    private static DeviceProtocolMapping mapping(long id, long deviceId, String protocol, String externalId) {
        DeviceProtocolMapping mapping = new DeviceProtocolMapping();
        mapping.id = id;
        mapping.deviceId = deviceId;
        mapping.protocol = protocol;
        mapping.thirdPartyDeviceId = externalId;
        mapping.enabled = true;
        return mapping;
    }

    private static DeviceInstance device(long id, String deviceCode, String protocol) {
        DeviceInstance device = new DeviceInstance();
        device.id = id;
        device.deviceCode = deviceCode;
        device.protocol = protocol;
        device.enabled = true;
        return device;
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
