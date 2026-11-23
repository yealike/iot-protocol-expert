package top.yealike.pep.gateway.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.pep.gateway.device.DeviceIdentityRegistry;

class ProtocolServerManagerTest {

    @Test
    void loadsCompleteIdentitySnapshotBeforeOpeningProtocolPorts() {
        DeviceIdentityRegistry identities = mock(DeviceIdentityRegistry.class);
        ProtocolServer server = mock(ProtocolServer.class);
        when(server.enabled()).thenReturn(true);
        when(server.protocol()).thenReturn(ProtocolType.YKC16);
        when(server.port()).thenReturn(8768);

        ProtocolServerManager manager = new ProtocolServerManager(List.of(server), identities);
        manager.start();

        inOrder(identities, server).verify(identities).load();
        verify(server).start();
    }

    @Test
    void mappingLoadFailurePreventsAnyListeningPort() {
        DeviceIdentityRegistry identities = mock(DeviceIdentityRegistry.class);
        ProtocolServer server = mock(ProtocolServer.class);
        when(server.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("Ops unavailable")).when(identities).load();

        ProtocolServerManager manager = new ProtocolServerManager(List.of(server), identities);

        assertThrows(IllegalStateException.class, manager::start);
        verify(server, never()).start();
    }
}
