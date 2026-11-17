package top.yealike.pep.runtime.execution;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.config.RuntimeProperties;

/** Run 并发 + 等待队列的统一准入；满额立即拒绝，不无限排队。 */
@Component
public class RunAdmission {

    private final Semaphore slots;
    private final int capacity;

    public RunAdmission(RuntimeProperties properties) {
        this.capacity = properties.run().maxActive() + properties.run().queueCapacity();
        this.slots = new Semaphore(capacity);
    }

    public boolean tryEnter() {
        return slots.tryAcquire();
    }

    public void leave() {
        slots.release();
    }

    public int available() {
        return slots.availablePermits();
    }

    public int capacity() {
        return capacity;
    }
}
