package top.yealike.ipe.ai.platform.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 发布 SQL 的绑定顺序与幂等重入验收；不 mock Store 本身。 */
class ComponentPublishSqlTest {

    private static final String VERSION = "1.2.3";
    private static final long GENERATION = 7L;

    @Test
    void mcpPublishBindsVersionAndGenerationInSqlOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID componentId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean published = new McpStore(jdbc).publishComponent(componentId, VERSION, GENERATION);

        assertThat(published).isTrue();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update ai_mcp_component"), arguments.capture());
        assertThat(arguments.getValue())
                .containsExactly(VERSION, GENERATION, componentId, GENERATION, VERSION, GENERATION);
    }

    @Test
    void skillPublishBindsVersionAndGenerationInSqlOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID skillId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean published = new SkillStore(jdbc).publishComponent(skillId, VERSION, GENERATION);

        assertThat(published).isTrue();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update ai_skill_component"), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(VERSION, GENERATION, skillId, GENERATION, VERSION, GENERATION);
    }

    @Test
    void mcpPublishTreatsCommittedGenerationAsIdempotentSuccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID componentId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        assertThat(new McpStore(jdbc).publishComponent(componentId, VERSION, GENERATION))
                .isTrue();
    }

    @Test
    void skillPublishRejectsStaleGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID skillId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        assertThat(new SkillStore(jdbc).publishComponent(skillId, VERSION, GENERATION))
                .isFalse();
    }
}
