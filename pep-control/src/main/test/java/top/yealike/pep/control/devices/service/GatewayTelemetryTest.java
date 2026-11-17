package top.yealike.pep.control.devices.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.alarms.service.AlarmService;
import top.yealike.pep.control.devices.entity.DeviceInstance;
import top.yealike.pep.control.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.control.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.control.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.control.devices.mapper.DevicePropertyTsMapper;
import top.yealike.pep.control.devices.thingmodel.ThingModel;
import top.yealike.pep.control.devices.thingmodel.ValueType;

class GatewayTelemetryTest {
    private final DeviceInstanceMapper devices = mock(DeviceInstanceMapper.class);
    private final DevicePropertyLatestMapper latest = mock(DevicePropertyLatestMapper.class);
    private final ThingModelResolver models = mock(ThingModelResolver.class);
    private DevicePropertyService service;
    private DeviceInstance device;

    @BeforeEach
    void setUp() {
        service = new DevicePropertyService(
                latest,
                mock(DevicePropertyTsMapper.class),
                devices,
                mock(DeviceChannelMapper.class),
                models,
                mock(AlarmService.class));
        device = new DeviceInstance();
        device.id = 1L;
        device.deviceCode = "SIM-1";
        device.protocolDeviceId = "SIM-1";
        when(devices.findByProtocolAndProtocolDeviceId("MODBUS_TCP", "SIM-1")).thenReturn(Optional.of(device));
    }

    @Test
    void rejectsDisabledDevicesAndSimulationOptOut() {
        device.enabled = false;
        assertThrows(
                IllegalArgumentException.class,
                () -> service.writeGatewayBatch("MODBUS_TCP", "SIM-1", "", Map.of("soc", 50), null));
        device.enabled = true;
        device.allowSimulate = false;
        assertThrows(
                IllegalArgumentException.class,
                () -> service.writeGatewayBatch("MODBUS_TCP", "SIM-1", "", Map.of("soc", 50), null));
        verifyNoInteractions(latest);
    }

    @Test
    void rejectsUnknownOrUnscopedChannelPropertiesBeforeWriting() {
        var soc = ThingModel.PropertyMetadata.of("soc", "SOC", new ValueType.IntType(0L, 100L, "%"));
        var pack = ThingModel.PropertyMetadata.channel("packSoc", "Pack", new ValueType.IntType(0L, 100L, "%"));
        when(models.of(device)).thenReturn(new ThingModel(List.of(soc, pack), List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.writeGatewayBatch("MODBUS_TCP", "SIM-1", "", Map.of("soc", 50, "unknown", 1), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.writeGatewayBatch("MODBUS_TCP", "SIM-1", "", Map.of("packSoc", 50), null));
        verifyNoInteractions(latest);
    }

    @Test
    void acceptedTelemetryCreatesGatewayLeaseButInvalidValueDoesNot() {
        device.deviceType = "STORAGE";
        var soc = ThingModel.PropertyMetadata.of("soc", "SOC", new ValueType.IntType(0L, 100L, "%"));
        when(models.of(device)).thenReturn(new ThingModel(List.of(soc), List.of(), List.of(), List.of()));
        assertEquals(0, service.writeGatewayBatch("MODBUS_TCP", "SIM-1", "", Map.of("soc", 150), null));
        verify(devices, never()).save(any());
        assertEquals(1, service.writeGatewayBatch("MODBUS_TCP", "SIM-1", "", Map.of("soc", 50), null));
        assertEquals(DeviceInstance.STATE_ONLINE, device.state);
        assertEquals("gateway-telemetry", device.sessionId);
        assertNotNull(device.lastSeen);
        verify(devices).save(device);
    }
}
