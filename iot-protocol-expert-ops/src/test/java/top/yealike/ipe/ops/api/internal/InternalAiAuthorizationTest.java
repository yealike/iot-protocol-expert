package top.yealike.ipe.ops.api.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.security.IdentityService;
import top.yealike.ipe.ops.service.BillService;
import top.yealike.ipe.ops.service.OrderService;
import top.yealike.ipe.ops.telemetry.OrderTelemetryService;

class InternalAiAuthorizationTest {
    @Test
    void claimedWildcardCannotOverrideUsersStationScope() {
        var orders = mock(OrderService.class);
        var identities = mock(IdentityService.class);
        var order = new ChargeOrder();
        order.stationId = 99L;
        when(orders.require("order")).thenReturn(order);
        when(identities.byId(1))
                .thenReturn(Optional.of(new IdentityService.Identity(
                        1, "alice", true, false, 0, List.of("OPS"), List.of("ai:use"), List.of(3L))));
        var controller = new InternalAiController(
                orders,
                mock(BillService.class),
                mock(DeviceInstanceService.class),
                mock(OrderTelemetryService.class),
                mock(JdbcTemplate.class),
                identities,
                ".");
        var request = new MockHttpServletRequest();
        request.addHeader("X-Ai-Actor", "1");
        request.addHeader("X-Ai-Station-Scope", "*");
        assertThrows(InternalAiController.StationOutOfScopeException.class, () -> controller.order(request, "order"));
    }
}
