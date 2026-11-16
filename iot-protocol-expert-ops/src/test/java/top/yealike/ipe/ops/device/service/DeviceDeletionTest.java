package top.yealike.ipe.ops.device.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.mapper.DeviceInstanceMapper;

class DeviceDeletionTest {
    @Test
    void historyPreventsDeletionAndConfigurationsAreCleanedBeforeMainRow() {
        var mapper = mock(DeviceInstanceMapper.class);
        var service = new DeviceInstanceService(mapper, null, null, null, null, null, null, null, null, null, null);
        var device = new DeviceInstance();
        device.id = 7L;
        device.state = DeviceInstance.STATE_NOT_ACTIVE;
        when(mapper.findById(7L)).thenReturn(Optional.of(device));
        when(mapper.hasDeletionBlockers(7L)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.delete(7L));
        verify(mapper, never()).delete(any());
        verify(mapper, never()).deleteTags(any());
        when(mapper.hasDeletionBlockers(7L)).thenReturn(false);
        service.delete(7L);
        var ordered = inOrder(mapper);
        ordered.verify(mapper).deleteTags(7L);
        ordered.verify(mapper).deleteChannels(7L);
        ordered.verify(mapper).deleteLatestProperties(7L);
        ordered.verify(mapper).delete(device);
    }
}
