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
import org.mockito.ArgumentCaptor;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.OrderExternalReference;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.OrderExternalReferenceMapper;
import top.yealike.pep.ops.charging.service.ChargeBillingService;
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

        Assertions.assertThrows(
                IllegalStateException.class, () -> fixture.service.bindOcppTransactionId("ORDER-1", BigDecimal.ZERO));

        assertEquals(ChargeOrder.Status.FAILED, fixture.order.status);
        verify(fixture.mapper, never()).save(any());
    }

    @Test
    void bindOcppTransactionIdStoresSequenceValueInExternalReference() {
        Fixture fixture = fixture(ChargeOrder.Status.PENDING);
        when(fixture.references.nextOcppTransactionId()).thenReturn(42L);

        int transactionId = fixture.service.bindOcppTransactionId("ORDER-1", new BigDecimal("1000"));

        assertEquals(42, transactionId);
        ArgumentCaptor<OrderExternalReference> captor = ArgumentCaptor.forClass(OrderExternalReference.class);
        verify(fixture.references).save(captor.capture());
        OrderExternalReference reference = captor.getValue();
        assertEquals(1L, reference.orderId);
        assertEquals(1L, reference.deviceId);
        assertEquals("OCPP16J", reference.protocol);
        assertEquals(OrderService.OCPP_TRANSACTION, reference.referenceType);
        assertEquals("42", reference.externalReference);
        assertEquals(new BigDecimal("1000"), fixture.order.meterStartWh);
        verify(fixture.mapper).save(fixture.order);
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
        OrderService service = new OrderService(
                mapper,
                mock(OrderExternalReferenceMapper.class),
                mock(DeviceService.class),
                mock(ChargeBillingService.class));

        service.expirePending();

        assertEquals(ChargeOrder.Status.FAILED, order.status);
        verify(mapper).findPendingBefore(any(), eq(200));
        verify(mapper, never()).findTop200ByOrderByIdDesc();
    }

    private static Fixture fixture(ChargeOrder.Status status) {
        ChargeOrderMapper mapper = mock(ChargeOrderMapper.class);
        OrderExternalReferenceMapper references = mock(OrderExternalReferenceMapper.class);
        ChargeOrder order = new ChargeOrder();
        order.id = 1L;
        order.orderNo = "ORDER-1";
        order.deviceId = 1L;
        order.protocol = "OCPP16J";
        order.status = status;
        when(mapper.selectByOrderNoForUpdate("ORDER-1")).thenReturn(order);
        when(mapper.lockByOrderNo("ORDER-1")).thenReturn(Optional.of(order));
        when(mapper.save(order)).thenReturn(order);
        when(references.findByOrder(1L, "OCPP16J", OrderService.OCPP_TRANSACTION))
                .thenReturn(Optional.empty());
        return new Fixture(
                new OrderService(mapper, references, mock(DeviceService.class), mock(ChargeBillingService.class)),
                mapper,
                references,
                order);
    }

    private record Fixture(
            OrderService service,
            ChargeOrderMapper mapper,
            OrderExternalReferenceMapper references,
            ChargeOrder order) {}
}
