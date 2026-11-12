package top.yealike.ipe.ops.device.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.Product;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.device.repository.ProductRepository;

class ProductBindingTest {
    @Test
    void newProductRequiresMatchingActiveDocument() {
        var products = mock(ProductRepository.class);
        var devices = mock(DeviceInstanceRepository.class);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new ProductService(products, devices, jdbc);
        when(products.findByProductCode("P2")).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString(), eq(42L))).thenReturn(java.util.List.of());
        var input = new DeviceDtos.ProductSave("P2", "name", "charging", "CHARGER", "OCPP16J", 42L, "WS", null, null, Map.of(), Map.of(), null, null, "DRAFT");
        assertThrows(IllegalArgumentException.class, () -> service.save(input));
        verify(products, never()).save(any());
    }

    @Test
    void existingDevicePreventsProtocolAndModelReplacement() {
        var products = mock(ProductRepository.class);
        var devices = mock(DeviceInstanceRepository.class);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new ProductService(products, devices, jdbc);
        when(jdbc.queryForList(anyString(), eq(42L))).thenReturn(java.util.List.of(Map.of("protocol_name_en", "OCPP", "document_version", "1.6j", "status", "ACTIVE")));
        var existing = new Product();
        existing.id = 7L;
        existing.productCode = "P1";
        existing.protocol = "YKC16";
        existing.transport = "TCP";
        existing.metadata = "{}";
        when(products.findByProductCode("P1")).thenReturn(Optional.of(existing));
        when(devices.countByProductId(7L)).thenReturn(1L);
        var input = new DeviceDtos.ProductSave("P1", "name", "charging", "CHARGER", "OCPP16J", 42L, "WS", null, null, Map.of(), Map.of(), null, null, "PUBLISHED");
        assertThrows(IllegalArgumentException.class, () -> service.save(input));
        verify(products, never()).save(any());
    }
}
