package top.yealike.ipe.ai.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RunEventServiceTest {
    @Test
    void publishesOnlyAfterCommit() {
        RunRepository repository = mock(RunRepository.class);
        ConversationChannels channels = mock(ConversationChannels.class);
        UUID conversation = UUID.randomUUID();
        when(repository.nextEventCursor(conversation)).thenReturn(1L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            new RunEventService(repository, channels).publish(conversation, UUID.randomUUID(), "run.started", Map.of());
            verify(channels, never()).publish(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(channels).publish(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
