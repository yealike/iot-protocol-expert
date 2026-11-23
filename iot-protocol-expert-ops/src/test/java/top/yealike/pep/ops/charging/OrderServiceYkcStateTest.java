package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.OrderExternalReference;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.OrderExternalReferenceMapper;
import top.yealike.pep.ops.charging.service.ChargeBillingService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.service.DeviceService;

class OrderServiceYkcStateTest {

    private static final String DEVICE_SN = "10000000";
    private static final String EXTERNAL_ID = "32010600000001";
    private static final String TRANSACTION_NO = "10000000012026112210000000000001";

    @Test
    void realtimeAdvancesYkcOrderLocatedByExternalTransactionNo() {
        Fixture fixture = fixture(ChargeOrder.Status.PENDING);

        fixture.service.applyRealtime(
                "YKC16", EXTERNAL_ID, "01", 3, new BigDecimal("2.5000"), new BigDecimal("3.1000"), TRANSACTION_NO);

        assertEquals(ChargeOrder.Status.CHARGING, fixture.order.status);
        assertEquals(new BigDecimal("2.5000"), fixture.order.energy);
        assertEquals(new BigDecimal("3.1000"), fixture.order.amount);
        assertNotNull(fixture.order.startTime);
        verify(fixture.references).findByExternal(1L, "YKC16", OrderService.YKC_TRANSACTION, TRANSACTION_NO);
        verify(fixture.orders, never()).findTopByDeviceIdAndGunNoAndStatusInOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void explicitYkcStartFailureCanFailOptimisticallyChargingOrder() {
        Fixture fixture = fixture(ChargeOrder.Status.CHARGING);

        fixture.service.failByTransaction("YKC16", EXTERNAL_ID, TRANSACTION_NO, "桩拒绝并充启动: 9");

        assertEquals(ChargeOrder.Status.FAILED, fixture.order.status);
        assertEquals("桩拒绝并充启动: 9", fixture.order.failReason);
        verify(fixture.references).findByExternal(1L, "YKC16", OrderService.YKC_TRANSACTION, TRANSACTION_NO);
        verify(fixture.orders).save(fixture.order);
    }

    private static Fixture fixture(ChargeOrder.Status status) {
        ChargeOrderMapper orders = mock(ChargeOrderMapper.class);
        OrderExternalReferenceMapper references = mock(OrderExternalReferenceMapper.class);
        DeviceService devices = mock(DeviceService.class);
        DeviceInstance device = new DeviceInstance();
        device.id = 1L;
        device.deviceCode = DEVICE_SN;
        ChargeOrder order = new ChargeOrder();
        order.id = 1L;
        order.orderNo = "202611221000000000001";
        order.deviceId = 1L;
        order.gunNo = "01";
        order.protocol = "YKC16";
        order.status = status;
        OrderExternalReference reference = new OrderExternalReference();
        reference.orderId = order.id;
        when(devices.resolve("YKC16", EXTERNAL_ID)).thenReturn(device);
        when(references.findByExternal(1L, "YKC16", OrderService.YKC_TRANSACTION, TRANSACTION_NO))
                .thenReturn(Optional.of(reference));
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        when(orders.lockByOrderNo(order.orderNo)).thenReturn(Optional.of(order));
        when(orders.save(order)).thenReturn(order);
        OrderService service = new OrderService(orders, references, devices, mock(ChargeBillingService.class));
        return new Fixture(service, orders, references, order);
    }

    private record Fixture(
            OrderService service,
            ChargeOrderMapper orders,
            OrderExternalReferenceMapper references,
            ChargeOrder order) {}
}
