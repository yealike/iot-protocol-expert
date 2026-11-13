package top.yealike.ipe.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ai.identity.UserIdentity;

class ToolSessionTest {
    @Test
    void concurrentSubagentsShareTheSameBudget() throws Exception {
        ToolSession session = new ToolSession(
                new UserIdentity("1", "test", List.of("OPS"), List.of("ai:use"), 0),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "team-leader",
                "v1",
                "1",
                10);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var tasks = java.util.stream.IntStream.range(0, 100)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(
                            i -> () -> session.forAgent("order-service").tryAcquireCall())
                    .toList();
            int accepted = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) accepted++;
            assertEquals(10, accepted);
            assertEquals(10, session.callCount());
        }
    }
}
