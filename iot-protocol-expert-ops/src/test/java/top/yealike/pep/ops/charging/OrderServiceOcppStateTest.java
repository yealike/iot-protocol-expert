package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.devices.service.DeviceService;

class OrderServiceOcppStateTest {

    @Test
    void failCannotOverwriteConfirmedChargingOrder() {
        Fixture fixture = fixture(ChargeOrder.Status.CHARGING);

        fixture.service.fail("ORDER-1", "late command timeout");

        assertEquals(ChargeOrder.Status.CHARGING, fixture.order.status);
        verify(fixture.mapper, never()).save(any());
    }

    @Test
    void failStillClosesPendingOrderOnExplicitRejection() {
        Fixture fixture = fixture(ChargeOrder.Status.PENDING);

        fixture.service.fail("ORDER-1", "device rejected");

        assertEquals(ChargeOrder.Status.FAILED, fixture.order.status);
        assertEquals("device rejected", fixture.order.failReason);
        verify(fixture.mapper).save(fixture.order);
    }

    @Test
    void startTransactionCannotReviveFailedOrder() {
        Fixture fixture = fixture(ChargeOrder.Status.FAILED);
        when(fixture.mapper.maxOcppTransactionId()).thenReturn(4L);

        Assertions.assertThrows(
                IllegalStateException.class, () -> fixture.service.bindOcppTransactionId("ORDER-1", BigDecimal.ZERO));

        assertEquals(ChargeOrder.Status.FAILED, fixture.order.status);
        verify(fixture.mapper, never()).save(any());
    }

    @Test
    void lateMeterCannotOverwriteSettledOrder() {
        Fixture fixture = fixture(ChargeOrder.Status.SETTLED);
        fixture.order.energy = new BigDecimal("5.0");

        fixture.service.applyMeter("ORDER-1", new BigDecimal("99.0"), new BigDecimal("999.0"));

        assertEquals(new BigDecimal("5.0"), fixture.order.energy);
        verify(fixture.mapper, never()).save(any());
    }

    @Test
    void expiryScansStalePendingOrdersInsteadOfOnlyTheNewestOrders() {
        ChargeOrderMapper mapper = mock(ChargeOrderMapper.class);
        ChargeOrder order = new ChargeOrder();
        order.orderNo = "ORDER-1";
        order.status = ChargeOrder.Status.PENDING;
        order.createdAt = LocalDateTime.now().minusMinutes(2);
        when(mapper.findPendingBefore(any(), eq(200))).thenReturn(List.of(order));
        when(mapper.lockByOrderNo("ORDER-1")).thenReturn(Optional.of(order));
        when(mapper.save(order)).thenReturn(order);
        OrderService service = new OrderService(mapper, mock(DeviceService.class));

        service.expirePending();

        assertEquals(ChargeOrder.Status.FAILED, order.status);
        verify(mapper).findPendingBefore(any(), eq(200));
        verify(mapper, never()).findTop200ByOrderByIdDesc();
    }

    private static Fixture fixture(ChargeOrder.Status status) {
        ChargeOrderMapper mapper = mock(ChargeOrderMapper.class);
        ChargeOrder order = new ChargeOrder();
        order.orderNo = "ORDER-1";
        order.status = status;
        when(mapper.selectByOrderNoForUpdate("ORDER-1")).thenReturn(order);
        when(mapper.lockByOrderNo("ORDER-1")).thenReturn(Optional.of(order));
        when(mapper.save(order)).thenReturn(order);
        return new Fixture(new OrderService(mapper, mock(DeviceService.class)), mapper, order);
    }

    private record Fixture(OrderService service, ChargeOrderMapper mapper, ChargeOrder order) {}
}
