package top.yealike.pep.control.agents.skill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import top.yealike.pep.control.support.AiExceptions;

class SkillServiceKeyTest {

    @Test
    void acceptsAgentSkillsCompatibleKeys() {
        assertThatCode(() -> SkillService.requireKey("ab")).doesNotThrowAnyException();
        assertThatCode(() -> SkillService.requireKey("device-offline-check")).doesNotThrowAnyException();
        assertThatCode(() -> SkillService.requireKey("a1-b2-c3")).doesNotThrowAnyException();
    }

    @Test
    void rejectsKeysThatViolateAgentSkillsNameRules() {
        for (String key : new String[] {"a", "-device", "device-", "device--offline", "Device-Check", "device_check"}) {
            assertThatThrownBy(() -> SkillService.requireKey(key))
                    .isInstanceOf(AiExceptions.AiException.class)
                    .hasMessageContaining("Skill key");
        }
    }
}
