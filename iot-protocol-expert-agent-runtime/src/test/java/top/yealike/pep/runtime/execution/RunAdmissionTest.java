package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import top.yealike.pep.runtime.TestFixtures;

class RunAdmissionTest {

    @Test
    void rejectsWhenConcurrencyPlusQueueIsFull() {
        RunAdmission admission = new RunAdmission(TestFixtures.properties());
        for (int index = 0; index < admission.capacity(); index++) {
            assertThat(admission.tryEnter()).isTrue();
        }
        assertThat(admission.tryEnter()).isFalse();
        admission.leave();
        assertThat(admission.tryEnter()).isTrue();
    }
}
