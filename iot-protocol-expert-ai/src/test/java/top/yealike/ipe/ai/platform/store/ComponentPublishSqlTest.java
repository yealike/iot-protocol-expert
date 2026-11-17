package top.yealike.ipe.ai.platform.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ai.mapper.McpPlatformMapper;
import top.yealike.ipe.ai.mapper.SkillPlatformMapper;

/** 发布代次推进与幂等重入验收；Store 只负责把语义委托给 MyBatis Mapper。 */
class ComponentPublishSqlTest {

    private static final String VERSION = "1.2.3";
    private static final long GENERATION = 7L;

    @Test
    void mcpPublishAdvancesGeneration() {
        McpPlatformMapper mapper = mock(McpPlatformMapper.class);
        UUID componentId = UUID.randomUUID();
        when(mapper.publishComponent(componentId, VERSION, GENERATION)).thenReturn(1);

        boolean published = new McpStore(mapper).publishComponent(componentId, VERSION, GENERATION);

        assertThat(published).isTrue();
        verify(mapper).publishComponent(componentId, VERSION, GENERATION);
        verify(mapper, never()).countPublished(any(), anyString(), anyLong());
    }

    @Test
    void skillPublishAdvancesGeneration() {
        SkillPlatformMapper mapper = mock(SkillPlatformMapper.class);
        UUID skillId = UUID.randomUUID();
        when(mapper.publishComponent(skillId, VERSION, GENERATION)).thenReturn(1);

        boolean published = new SkillStore(mapper).publishComponent(skillId, VERSION, GENERATION);

        assertThat(published).isTrue();
        verify(mapper).publishComponent(skillId, VERSION, GENERATION);
        verify(mapper, never()).countPublished(any(), anyString(), anyLong());
    }

    @Test
    void mcpPublishTreatsCommittedGenerationAsIdempotentSuccess() {
        McpPlatformMapper mapper = mock(McpPlatformMapper.class);
        UUID componentId = UUID.randomUUID();
        when(mapper.publishComponent(componentId, VERSION, GENERATION)).thenReturn(0);
        when(mapper.countPublished(componentId, VERSION, GENERATION)).thenReturn(1);

        assertThat(new McpStore(mapper).publishComponent(componentId, VERSION, GENERATION))
                .isTrue();
    }

    @Test
    void skillPublishRejectsStaleGeneration() {
        SkillPlatformMapper mapper = mock(SkillPlatformMapper.class);
        UUID skillId = UUID.randomUUID();
        when(mapper.publishComponent(skillId, VERSION, GENERATION)).thenReturn(0);
        when(mapper.countPublished(skillId, VERSION, GENERATION)).thenReturn(0);

        assertThat(new SkillStore(mapper).publishComponent(skillId, VERSION, GENERATION))
                .isFalse();
    }
}
