package top.yealike.ipe.ops.device.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.Product;
import top.yealike.ipe.ops.mapper.DeviceInstanceMapper;
import top.yealike.ipe.ops.mapper.ProductMapper;
import top.yealike.ipe.ops.mapper.ProtocolDocumentMapper;

class ProductBindingTest {
    @Test
    void newProductRequiresMatchingActiveDocument() {
        var products = mock(ProductMapper.class);
        var devices = mock(DeviceInstanceMapper.class);
        var documents = mock(ProtocolDocumentMapper.class);
        var service = new ProductService(products, devices, documents);
        when(products.findByProductCode("P2")).thenReturn(Optional.empty());
        when(documents.selectPolicyForUpdate(42L)).thenReturn(null);
        var input = new DeviceDtos.ProductSave(
                "P2",
                "name",
                "charging",
                "CHARGER",
                "OCPP16J",
                42L,
                "WS",
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                "DRAFT");
        assertThrows(IllegalArgumentException.class, () -> service.save(input));
        verify(products, never()).save(any());
    }

    @Test
    void existingDevicePreventsProtocolAndModelReplacement() {
        var products = mock(ProductMapper.class);
        var devices = mock(DeviceInstanceMapper.class);
        var documents = mock(ProtocolDocumentMapper.class);
        var service = new ProductService(products, devices, documents);
        when(documents.selectPolicyForUpdate(42L))
                .thenReturn(new ProtocolDocumentMapper.Policy("OCPP", "1.6j", "ACTIVE"));
        var existing = new Product();
        existing.id = 7L;
        existing.productCode = "P1";
        existing.protocol = "YKC16";
        existing.transport = "TCP";
        existing.metadata = "{}";
        when(products.findByProductCode("P1")).thenReturn(Optional.of(existing));
        when(devices.countByProductId(7L)).thenReturn(1L);
        var input = new DeviceDtos.ProductSave(
                "P1",
                "name",
                "charging",
                "CHARGER",
                "OCPP16J",
                42L,
                "WS",
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                "PUBLISHED");
        assertThrows(IllegalArgumentException.class, () -> service.save(input));
        verify(products, never()).save(any());
    }
}
