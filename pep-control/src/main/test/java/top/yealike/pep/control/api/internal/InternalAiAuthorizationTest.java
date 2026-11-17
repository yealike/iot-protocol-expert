package top.yealike.pep.control.api.internal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.yealike.pep.control.config.OpsProperties;
import top.yealike.pep.control.devices.service.DeviceInstanceService;
import top.yealike.pep.control.charging.domain.ChargeOrder;
import top.yealike.pep.control.devices.mapper.ProtocolDocumentMapper;
import top.yealike.pep.control.charging.mapper.StationMapper;
import top.yealike.pep.control.access.IdentityService;
import top.yealike.pep.control.charging.service.BillService;
import top.yealike.pep.control.charging.service.OrderService;
import top.yealike.pep.control.telemetry.OrderTelemetryService;

class InternalAiAuthorizationTest {
    @Test
    void claimedWildcardCannotOverrideUsersStationScope() throws Exception {
        var orders = mock(OrderService.class);
        var identities = mock(IdentityService.class);
        var order = new ChargeOrder();
        order.stationId = 99L;
        when(orders.require("order")).thenReturn(order);
        when(identities.byId(1))
                .thenReturn(Optional.of(new IdentityService.Identity(
                        1, "alice", true, false, 0, List.of("OPS"), List.of("ai:use"), List.of(3L))));
        String secret = "test-only-shared-key";
        var properties = new OpsProperties();
        properties.setAiMcpToken(secret);
        var controller = new OpsAiRestController(
                orders,
                mock(BillService.class),
                mock(DeviceInstanceService.class),
                mock(OrderTelemetryService.class),
                mock(ProtocolDocumentMapper.class),
                mock(StationMapper.class),
                identities,
                mock(OpsSessionLookup.class),
                new AiContextCodec(properties),
                ".");
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        var key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("get_order_detail".getBytes(StandardCharsets.UTF_8));
        byte[] payload = cipher.doFinal(
                ("""
                {"actor":"1","scope":"*","expires":%d}
                """.formatted(Instant.now().plusSeconds(30).getEpochSecond())).getBytes(StandardCharsets.UTF_8));
        String context = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(java.nio.ByteBuffer.allocate(nonce.length + payload.length)
                        .put(nonce)
                        .put(payload)
                        .array());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/internal/ai/rest/get_order_detail")
                        .contentType("application/json")
                        .content("""
                                {"order_no":"order","_ipe_context":"%s"}
                                """.formatted(context)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/ai/rest/get_order_bills")
                        .contentType("application/json")
                        .content("""
                                {"order_no":"order","_ipe_context":"%s"}
                                """.formatted(context)))
                .andExpect(status().isUnauthorized());
        verify(orders, times(1)).require("order");
    }
}
