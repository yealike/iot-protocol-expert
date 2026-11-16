package top.yealike.ipe.ai.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.ipe.ai.conversation.ConversationService;
import top.yealike.ipe.ai.identity.IdentityService;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.observability.AiObservability;
import top.yealike.ipe.ai.run.RunEventService;

class EventControllerTest {
    @Test
    void capsStreamsAndReleasesCapacityWhenPollingFails() {
        var observations = mock(AiObservability.class);
        var identities = mock(IdentityService.class);
        var polls = new java.util.ArrayList<Runnable>();
        when(observations.wrap(any())).thenAnswer(invocation -> {
            polls.add(invocation.getArgument(0));
            return (Runnable) () -> {};
        });
        var controller = new EventController(
                mock(RunEventService.class), mock(ConversationService.class), identities, observations);
        var identity = new UserIdentity("1", "admin", List.of("ADMIN"), List.of(), 0);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        var request = new MockHttpServletRequest();
        request.addHeader("Cookie", "test");
        var id = UUID.randomUUID();
        try {
            for (int i = 0; i < 256; i++) controller.stream(id, 0, null, request);
            var rejection = assertThrows(ResponseStatusException.class, () -> controller.stream(id, 0, null, request));
            assertEquals(429, rejection.getStatusCode().value());
            when(identities.resolve("test")).thenThrow(new IllegalStateException("revoked"));
            polls.getFirst().run();
            polls.getFirst().run(); // 重复结束不能重复释放配额。
            assertDoesNotThrow(() -> controller.stream(id, 0, null, request));
            assertThrows(ResponseStatusException.class, () -> controller.stream(id, 0, null, request));
        } finally {
            controller.shutdown();
            SecurityContextHolder.clearContext();
        }
    }
}
