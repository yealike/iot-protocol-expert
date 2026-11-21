package top.yealike.pep.gateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.pep.gateway.ops.OpsClient;
import top.yealike.pep.gateway.ops.OpsDtos;

class DeviceIdentityRegistryTest {

    @Test
    void loadsAllPagesAndResolvesPlatformAndThirdPartyIdentities() {
        OpsClient ops = mock(OpsClient.class);
        when(ops.deviceMappings(0, 500))
                .thenReturn(page(List.of(row(1, "10000000", "YKC16", "32010600000001", "1000000001", "1")), true, 1L));
        when(ops.deviceMappings(1, 500))
                .thenReturn(
                        page(List.of(row(2, "10000001", "OCPP16J", "CP-Exact-Case", "1000000102", "2")), false, 2L));

        DeviceIdentityRegistry registry = new DeviceIdentityRegistry(ops);
        registry.load();

        DeviceIdentityRegistry.Device ykc = registry.resolve(ProtocolType.YKC16, "32010600000001");
        assertEquals("10000000", ykc.deviceSn());
        assertEquals("1000000001", ykc.connectorByExternalNo("1").platformGunNo());
        assertEquals("01", ykc.connectorByExternalNo("01").externalYkcGunNo());

        DeviceIdentityRegistry.Device ocpp = registry.resolve(ProtocolType.OCPP16J, "CP-Exact-Case");
        assertEquals("10000001", ocpp.deviceSn());
        assertEquals(2, ocpp.connectorByPlatformGunNo("02").externalOcppConnectorId());
        assertThrows(IllegalStateException.class, () -> registry.resolve(ProtocolType.OCPP16J, "cp-exact-case"));
        assertThrows(IllegalStateException.class, () -> registry.resolve(ProtocolType.OCPP16J, "10000000"));
    }

    @Test
    void rejectsDuplicateExternalDeviceIdsAcrossPages() {
        OpsClient ops = mock(OpsClient.class);
        when(ops.deviceMappings(0, 500))
                .thenReturn(page(List.of(row(1, "10000000", "YKC16", "32010600000001", "1000000001", "1")), true, 1L));
        when(ops.deviceMappings(1, 500))
                .thenReturn(page(List.of(row(2, "10000001", "YKC16", "32010600000001", "1000000101", "1")), false, 2L));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> new DeviceIdentityRegistry(ops).load());

        assertEquals("第三方设备号重复映射：YKC16/32010600000001", error.getMessage());
    }

    @Test
    void rejectsNonAdvancingPageCursor() {
        OpsClient ops = mock(OpsClient.class);
        when(ops.deviceMappings(0, 500))
                .thenReturn(page(List.of(row(1, "10000000", "YKC16", "32010600000001", "1000000001", "1")), true, 0L));

        assertThrows(IllegalStateException.class, () -> new DeviceIdentityRegistry(ops).load());
    }

    @Test
    void tenDigitOcppChargePointIdRemainsAnExternalIdentity() {
        OpsClient ops = mock(OpsClient.class);
        when(ops.deviceMappings(0, 500))
                .thenReturn(page(List.of(row(1, "10000001", "OCPP16J", "1000000102", "1000000101", "1")), false, 1L));

        DeviceIdentityRegistry registry = new DeviceIdentityRegistry(ops);
        registry.load();

        assertEquals(
                "10000001",
                registry.resolveExternal(ProtocolType.OCPP16J, "1000000102").deviceSn());
        assertEquals(
                "10000001", registry.resolve(ProtocolType.OCPP16J, "1000000102").deviceSn());
        assertEquals(
                "1000000101",
                registry.resolvePlatform(ProtocolType.OCPP16J, "1000000101")
                        .connectors()
                        .values()
                        .iterator()
                        .next()
                        .platformGunNo());
    }

    private static OpsDtos.DeviceMappingPage page(
            List<OpsDtos.DeviceMappingRow> rows, boolean hasMore, Long nextAfterId) {
        return new OpsDtos.DeviceMappingPage(rows, hasMore, nextAfterId, "snapshot-1");
    }

    private static OpsDtos.DeviceMappingRow row(
            long id, String deviceSn, String protocol, String externalId, String platformGunNo, String connectorNo) {
        return new OpsDtos.DeviceMappingRow(
                id,
                deviceSn,
                protocol,
                externalId,
                true,
                List.of(new OpsDtos.DeviceMappingConnector(platformGunNo, connectorNo)));
    }
}
